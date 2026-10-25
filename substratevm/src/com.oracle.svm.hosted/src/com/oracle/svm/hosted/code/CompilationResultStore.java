/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.code;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import com.oracle.svm.core.SubstrateTarget;
import com.oracle.svm.core.graal.code.SharedCompilationResult;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.hosted.code.aarch64.AArch64HostedPatcherFeature;
import com.oracle.svm.core.meta.SubstrateMethodPointerConstant;
import com.oracle.svm.hosted.image.RelocatableBuffer;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.shared.util.ClassUtil;
import com.oracle.svm.shared.util.LogUtils;
import com.oracle.svm.shared.util.VMError;

import jdk.graal.compiler.asm.aarch64.AArch64Assembler.PatcherUtil;
import jdk.graal.compiler.asm.aarch64.AArch64MacroAssembler;
import jdk.graal.compiler.asm.aarch64.AArch64Assembler.SingleInstructionAnnotation;
import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.code.DataSection;
import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.options.OptionValues;
import jdk.vm.ci.aarch64.AArch64;
import jdk.vm.ci.code.BytecodeFrame;
import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.VirtualObject;
import com.oracle.graal.pointsto.heap.ImageHeapInstance;
import com.oracle.graal.pointsto.heap.ImageHeapObjectArray;
import com.oracle.graal.pointsto.heap.ImageHeapPrimitiveArray;
import com.oracle.objectfile.ObjectFile;
import com.oracle.svm.core.graal.code.CGlobalDataDirectReference;
import com.oracle.svm.core.heap.ReferenceMapEncoder;
import com.oracle.svm.core.heap.SubstrateReferenceMap;

import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.ConstantReference;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.DataSectionReference;
import jdk.vm.ci.code.site.ExceptionHandler;
import jdk.vm.ci.code.site.Infopoint;
import jdk.vm.ci.code.site.InfopointReason;
import jdk.vm.ci.code.site.Reference;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaValue;
import jdk.vm.ci.meta.ResolvedJavaMethod;

/**
 * Experimental cross-build cache for per-method {@link CompilationResult}s, keyed by a fingerprint
 * of the method bytecode and build environment (see
 * {@link CompileQueue#compilationFingerprint}). Results with data patches (embedded constants) or
 * with infopoints that carry {@link jdk.vm.ci.code.DebugInfo} are not cacheable in this version;
 * everything else (machine code, marks, exception handlers, and infopoints without debug info,
 * including call sites) is serialized and restored. A cache hit must still go through
 * {@code CompileQueue.ensureCalleesCompiled} to propagate compilations of callees.
 */
final class CompilationResultStore {

    private static final int MAGIC = 0x43525354;
    private static final int VERSION = 11;
    private static final String CACHE_FILE_SUFFIX = ".bin";
    private static final String INDEX_FILE_NAME = "index.txt";

    /** Fingerprint index per cache directory, to avoid one stat call per cache miss. */
    private static final ConcurrentHashMap<String, Set<String>> FINGERPRINT_INDEX = new ConcurrentHashMap<>();

    /** Temporary diagnostics for cache-whitelist tuning; removed once the whitelist is settled. */
    static final ConcurrentHashMap<String, LongAdder> REJECT_STATS = new ConcurrentHashMap<>();

    private CompilationResultStore() {
    }

    private static void reject(String reason) {
        REJECT_STATS.computeIfAbsent(reason, r -> new LongAdder()).increment();
    }

    private static void accept(String kind) {
        REJECT_STATS.computeIfAbsent("ACCEPTED-" + kind, r -> new LongAdder()).increment();
    }

    /** Feasibility spike: complexity histogram of the debugInfo wall. */
    private static void diagnoseDebugInfo(DebugInfo debugInfo) {
        if (debugInfo.getReferenceMap() != null) {
            reject("dbg-refmap:" + ClassUtil.getUnqualifiedName(debugInfo.getReferenceMap().getClass()));
        }
        VirtualObject[] virtualObjects = debugInfo.getVirtualObjectMapping();
        if (virtualObjects != null && virtualObjects.length > 0) {
            reject("dbg-vobj-frames:" + Math.min(virtualObjects.length, 4));
        }
        int depth = 0;
        for (BytecodeFrame frame = debugInfo.frame(); frame != null; frame = frame.caller()) {
            depth++;
            if (frame.values != null) {
                for (JavaValue value : frame.values) {
                    if (value == null) {
                        reject("dbg-val:null");
                    } else if (value instanceof StackSlot) {
                        reject("dbg-val:StackSlot");
                    } else if (value instanceof VirtualObject) {
                        reject("dbg-val:VirtualObject");
                    } else if (value instanceof JavaConstant constant) {
                        reject("dbg-val:const" + (constant.getJavaKind().isObject() ? "-object:" + ClassUtil.getUnqualifiedName(value.getClass()) : "-" + constant.getJavaKind().name()));
                    } else {
                        reject("dbg-val:" + ClassUtil.getUnqualifiedName(value.getClass()));
                    }
                }
            }
        }
        reject("dbg-depth:" + Math.min(depth, 6));
    }

    /**
     * Debug info is serialized without local values: for regular methods the image encodes only
     * method, bci, and frame sizes (see {@code FrameInfoEncoder.initializeFrameInfo}, guarded by
     * {@code needLocalValues}), plus the reference map for GC. Methods that need local values
     * (deopt testing, frame-information-required) are not cacheable.
     */
    private static boolean isCacheableDebugInfo(DebugInfo debugInfo) {
        if (debugInfo.getVirtualObjectMapping() != null && debugInfo.getVirtualObjectMapping().length > 0) {
            return false;
        }
        for (BytecodeFrame frame = debugInfo.frame(); frame != null; frame = frame.caller()) {
            if (!(frame.getMethod() instanceof HostedMethod) || SubstrateCompilationDirectives.singleton().isFrameInformationRequired(frame.getMethod())) {
                return false;
            }
        }
        return true;
    }

    private static final byte TAG_NULL = 0;
    private static final byte TAG_STRING = 1;
    private static final byte TAG_CLASS = 2;
    private static final byte TAG_ENUM = 3;
    private static final byte TAG_BOXED = 4;
    private static final byte TAG_PARRAY = 5;
    private static final byte TAG_EMPTY_LIST = 6;
    private static final byte TAG_EMPTY_SET = 7;
    private static final byte TAG_EMPTY_MAP = 8;
    private static final byte TAG_PRIMCONST = 9;
    private static final byte TAG_NULLCONST = 10;
    private static final byte TAG_IHINST = 11;
    private static final byte TAG_PHARRAY = 12;
    private static final byte TAG_OBJARRAY = 13;

    /** Encodes one field value of an image heap constant. Non-encodable values throw. */
    private static void writeHostedValue(DataOutputStream out, Object value, int depth) throws IOException {
        if (value == null) {
            out.writeByte(TAG_NULL);
        } else if (value instanceof ImageHeapPrimitiveArray primitiveArray) {
            out.writeByte(TAG_PHARRAY);
            out.writeUTF(primitiveArray.getType().getName());
            out.writeInt(primitiveArray.getLength());
            for (int i = 0; i < primitiveArray.getLength(); i++) {
                writeHostedValue(out, primitiveArray.readElementValue(i), depth);
            }
        } else if (value instanceof ImageHeapObjectArray objectArray) {
            out.writeByte(TAG_OBJARRAY);
            out.writeUTF(objectArray.getType().getName());
            out.writeInt(objectArray.getLength());
            for (int i = 0; i < objectArray.getLength(); i++) {
                writeHostedValue(out, objectArray.getElement(i), depth);
            }
        } else if (value instanceof JavaConstant constant && constant.isNull()) {
            out.writeByte(TAG_NULLCONST);
        } else if (value instanceof JavaConstant constant && !constant.getJavaKind().isObject()) {
            out.writeByte(TAG_PRIMCONST);
            out.writeUTF(constant.getJavaKind().name());
            switch (constant.getJavaKind()) {
                case Boolean -> out.writeBoolean(constant.asBoolean());
                case Byte -> out.writeByte(constant.asInt());
                case Short -> out.writeShort(constant.asInt());
                case Char -> out.writeChar(constant.asInt());
                case Int -> out.writeInt(constant.asInt());
                case Float -> out.writeFloat(constant.asFloat());
                case Long -> out.writeLong(constant.asLong());
                case Double -> out.writeDouble(constant.asDouble());
                default -> throw new IOException("unknown primitive kind " + constant.getJavaKind());
            }
        } else if (value instanceof ImageHeapInstance nested) {
            out.writeByte(TAG_IHINST);
            writeImageHeapConstant(out, nested, depth + 1);
        } else {
            throw new IOException("non-encodable hosted value: " + ClassUtil.getUnqualifiedName(value.getClass()));
        }
    }

    private static Object readHostedValue(DataInputStream in, Function<String, com.oracle.graal.pointsto.meta.AnalysisType> typeResolver) throws IOException {
        byte tag = in.readByte();
        switch (tag) {
            case TAG_NULL:
                return null;





            case TAG_PHARRAY: {
                Class<?> componentType = classForName(in.readUTF());
                int length = in.readInt();
                Object array = java.lang.reflect.Array.newInstance(componentType, length);
                for (int i = 0; i < length; i++) {
                    java.lang.reflect.Array.set(array, i, readHostedValue(in, typeResolver));
                }
                return array;
            }
            case TAG_OBJARRAY: {
                Class<?> componentType = classForName(in.readUTF());
                int length = in.readInt();
                Object array = java.lang.reflect.Array.newInstance(componentType, length);
                for (int i = 0; i < length; i++) {
                    java.lang.reflect.Array.set(array, i, readHostedValue(in, typeResolver));
                }
                return array;
            }
            case TAG_NULLCONST:
                return null;
            case TAG_PRIMCONST: {
                return switch (JavaKind.valueOf(in.readUTF())) {
                    case Boolean -> in.readBoolean();
                    case Byte -> in.readByte();
                    case Short -> in.readShort();
                    case Char -> in.readChar();
                    case Int -> in.readInt();
                    case Float -> in.readFloat();
                    case Long -> in.readLong();
                    case Double -> in.readDouble();
                    default -> throw new IOException("unknown primitive kind");
                };
            }
            case TAG_IHINST:
                return readImageHeapConstant(in, typeResolver);

            default:
                throw new IOException("unknown value tag " + tag);
        }
    }

    private static Class<?> classForName(String name) throws IOException {
        try {
            return Class.forName(name, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new IOException("cannot load class " + name, ex);
        }
    }

    private static void writeImageHeapConstant(DataOutputStream out, ImageHeapInstance constant) throws IOException {
        writeImageHeapConstant(out, constant, 0);
    }

    private static void writeImageHeapConstant(DataOutputStream out, ImageHeapInstance constant, int depth) throws IOException {
        if (depth > 3) {
            /* The object graph of image heap constants can be cyclic. */
            throw new IOException("image heap constant nesting too deep");
        }
        String typeName = constant.getType().getName();
        if (typeName.equals("java.lang.Class") || typeName.endsWith("DynamicHub")) {
            /* Hubs and Class mirrors get dedicated treatment in the heap writer; skip them. */
            throw new IOException("hub-like constant");
        }
        out.writeUTF(typeName);
        Object[] fieldValues = constant.hostedFieldValues();
        jdk.vm.ci.meta.ResolvedJavaField[] fields = constant.getType().getInstanceFields(false);
        if (fieldValues == null || fieldValues.length != fields.length) {
            /* A reader-based constant without materialized values cannot be rebuilt. */
            throw new IOException("no materialized field values");
        }
        out.writeInt(fieldValues.length);
        for (int i = 0; i < fieldValues.length; i++) {
            out.writeUTF(fields[i].getName());
            writeHostedValue(out, fieldValues[i], depth);
        }
    }

    private static ImageHeapInstance readImageHeapConstant(DataInputStream in, Function<String, com.oracle.graal.pointsto.meta.AnalysisType> typeResolver) throws IOException {
        String typeName = in.readUTF();
        com.oracle.graal.pointsto.meta.AnalysisType type = typeResolver.apply(typeName);
        if (type == null) {
            throw new IOException("cannot resolve type " + typeName);
        }
        int fieldCount = in.readInt();
        String[] fieldNames = new String[fieldCount];
        Object[] fieldValues = new Object[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            fieldNames[i] = in.readUTF();
            fieldValues[i] = readHostedValue(in, typeResolver);
        }
        try {
            Class<?> javaClass = classForName(typeName);
            Object hostedObject = UnsafeProxy.ALLOCATE.invoke(UnsafeProxy.UNSAFE, javaClass);
            for (int i = 0; i < fieldCount; i++) {
                java.lang.reflect.Field field = findField(javaClass, fieldNames[i]);
                setFieldValue(hostedObject, field, fieldValues[i]);
            }
            return new ImageHeapInstance(type, com.oracle.svm.core.meta.SubstrateObjectConstant.forObject(hostedObject), -1, -1);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("cannot rebuild hosted object of " + typeName, ex);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> javaClass, String name) throws IOException {
        for (Class<?> c = javaClass; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ex) {
                /* continue up the hierarchy */
            }
        }
        throw new IOException("no field " + name + " in " + javaClass.getName());
    }

    private static void setFieldValue(Object target, java.lang.reflect.Field field, Object boxedValue) throws IOException {
        try {
            Object unsafe = UnsafeProxy.UNSAFE;
            long offset = (Long) UnsafeProxy.OFFSET.invoke(unsafe, field);
            Class<?> type = field.getType();
            if (type == int.class) {
                UnsafeProxy.PUT_INT.invoke(unsafe, target, offset, boxedValue == null ? 0 : (Integer) boxedValue);
            } else if (type == long.class) {
                UnsafeProxy.PUT_LONG.invoke(unsafe, target, offset, boxedValue == null ? 0L : (Long) boxedValue);
            } else if (type == boolean.class) {
                UnsafeProxy.PUT_BOOL.invoke(unsafe, target, offset, boxedValue != null && (Boolean) boxedValue);
            } else if (type == byte.class) {
                UnsafeProxy.PUT_BYTE.invoke(unsafe, target, offset, boxedValue == null ? 0 : (Byte) boxedValue);
            } else if (type == short.class) {
                UnsafeProxy.PUT_SHORT.invoke(unsafe, target, offset, boxedValue == null ? 0 : (Short) boxedValue);
            } else if (type == char.class) {
                UnsafeProxy.PUT_CHAR.invoke(unsafe, target, offset, boxedValue == null ? 0 : (Character) boxedValue);
            } else if (type == float.class) {
                UnsafeProxy.PUT_FLOAT.invoke(unsafe, target, offset, boxedValue == null ? 0F : (Float) boxedValue);
            } else if (type == double.class) {
                UnsafeProxy.PUT_DOUBLE.invoke(unsafe, target, offset, boxedValue == null ? 0D : (Double) boxedValue);
            } else {
                UnsafeProxy.PUT_OBJ.invoke(unsafe, target, offset, boxedValue);
            }
        } catch (java.lang.reflect.InvocationTargetException | IllegalAccessException ex) {
            throw new IOException("cannot write field " + field.getName() + " of " + target.getClass().getName(), ex);
        }
    }

    /** Reflective sun.misc.Unsafe access: no compile-time dependency on the unexported package. */
    private static final class UnsafeProxy {
        static final Object UNSAFE = unsafeInstance();
        static final Class<?> CLS = unsafeClass();
        static final java.lang.reflect.Method ALLOCATE = method("allocateInstance", Class.class);
        static final java.lang.reflect.Method OFFSET = method("objectFieldOffset", java.lang.reflect.Field.class);
        static final java.lang.reflect.Method PUT_OBJ = method("putObject", Object.class, long.class, Object.class);
        static final java.lang.reflect.Method PUT_INT = method("putInt", Object.class, long.class, int.class);
        static final java.lang.reflect.Method PUT_LONG = method("putLong", Object.class, long.class, long.class);
        static final java.lang.reflect.Method PUT_BOOL = method("putBoolean", Object.class, long.class, boolean.class);
        static final java.lang.reflect.Method PUT_BYTE = method("putByte", Object.class, long.class, byte.class);
        static final java.lang.reflect.Method PUT_SHORT = method("putShort", Object.class, long.class, short.class);
        static final java.lang.reflect.Method PUT_CHAR = method("putChar", Object.class, long.class, char.class);
        static final java.lang.reflect.Method PUT_FLOAT = method("putFloat", Object.class, long.class, float.class);
        static final java.lang.reflect.Method PUT_DOUBLE = method("putDouble", Object.class, long.class, double.class);

        private static Class<?> unsafeClass() {
            try {
                return Class.forName("sun.misc.Unsafe");
            } catch (ClassNotFoundException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }

        private static Object unsafeInstance() {
            try {
                java.lang.reflect.Field theUnsafe = CLS.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return theUnsafe.get(null);
            } catch (ReflectiveOperationException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }

        private static java.lang.reflect.Method method(String name, Class<?>... params) {
            try {
                return CLS.getMethod(name, params);
            } catch (NoSuchMethodException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }
    }

    private static void writeDebugInfo(DataOutputStream out, DebugInfo debugInfo) throws IOException {
        int frameCount = 0;
        for (BytecodeFrame frame = debugInfo.frame(); frame != null; frame = frame.caller()) {
            frameCount++;
        }
        out.writeInt(frameCount);
        for (BytecodeFrame frame = debugInfo.frame(); frame != null; frame = frame.caller()) {
            out.writeUTF(CompileQueue.methodCacheKey((HostedMethod) frame.getMethod()));
            out.writeInt(frame.getBCI());
            out.writeBoolean(frame.rethrowException);
            out.writeBoolean(frame.duringCall);
            out.writeInt(frame.numLocals);
            out.writeInt(frame.numStack);
            out.writeInt(frame.numLocks);
        }
        java.util.ArrayList<Integer> refOffsets = new java.util.ArrayList<>();
        java.util.ArrayList<Boolean> refCompressed = new java.util.ArrayList<>();
        if (debugInfo.getReferenceMap() instanceof ReferenceMapEncoder.Input input && !input.isEmpty()) {
            ReferenceMapEncoder.OffsetIterator offsets = input.getOffsets();
            while (offsets.hasNext()) {
                boolean compressed = offsets.isNextCompressed();
                refOffsets.add(offsets.nextInt());
                refCompressed.add(compressed);
            }
        }
        out.writeInt(refOffsets.size());
        for (int i = 0; i < refOffsets.size(); i++) {
            out.writeInt(refOffsets.get(i));
            out.writeBoolean(refCompressed.get(i));
        }
    }

    private static DebugInfo readDebugInfo(DataInputStream in, Function<String, HostedMethod> methodKeyResolver) throws IOException {
        int frameCount = in.readInt();
        HostedMethod[] frameMethods = new HostedMethod[frameCount];
        int[] bci = new int[frameCount];
        boolean[] rethrow = new boolean[frameCount];
        boolean[] duringCall = new boolean[frameCount];
        int[] numLocals = new int[frameCount];
        int[] numStack = new int[frameCount];
        int[] numLocks = new int[frameCount];
        for (int f = 0; f < frameCount; f++) {
            frameMethods[f] = methodKeyResolver.apply(in.readUTF());
            bci[f] = in.readInt();
            rethrow[f] = in.readBoolean();
            duringCall[f] = in.readBoolean();
            numLocals[f] = in.readInt();
            numStack[f] = in.readInt();
            numLocks[f] = in.readInt();
        }
        BytecodeFrame frame = null;
        for (int f = frameCount - 1; f >= 0; f--) {
            if (frameMethods[f] == null) {
                return null;
            }
            frame = new BytecodeFrame(frame, frameMethods[f], bci[f], rethrow[f], duringCall[f], new JavaValue[0], new JavaKind[0], numLocals[f], numStack[f], numLocks[f]);
        }
        DebugInfo debugInfo = new DebugInfo(frame);
        int refCount = in.readInt();
        if (refCount > 0) {
            SubstrateReferenceMap referenceMap = new SubstrateReferenceMap();
            for (int i = 0; i < refCount; i++) {
                referenceMap.markReferenceAtOffset(in.readInt(), in.readBoolean());
            }
            debugInfo.setReferenceMap(referenceMap);
        }
        return debugInfo;
    }

    static boolean isCacheable(CompilationResult result, boolean conservative) {
        boolean hasDataPatches = !result.getDataPatches().isEmpty();
        if (conservative && hasDataPatches) {
            reject("conservative:dp");
            return false;
        }
        if (hasDataPatches) {
            for (DataPatch dataPatch : result.getDataPatches()) {
                if (!(dataPatch.reference instanceof ConstantReference constantReference) ||
                                !(constantReference.getConstant() instanceof ImageHeapInstance)) {
                    reject("dp:not-instance");
                    return false;
                }
            }
        }
        String passthroughTag = hasDataPatches && result.getDataPatches().stream().allMatch(
                        dp -> dp.reference instanceof ConstantReference cr && cr.getConstant() instanceof com.oracle.svm.core.graal.nodes.TLABObjectHeaderConstant)
                        ? "tlab" : "";
        for (Infopoint infopoint : result.getInfopoints()) {
            if (infopoint.debugInfo != null && !isCacheableDebugInfo(infopoint.debugInfo)) {
                reject("ip-uncacheable-debuginfo");
                return false;
            }
            if (infopoint instanceof Call call) {
                if (!(call.target instanceof HostedMethod)) {
                    reject("call-target-not-hosted");
                    return false;
                }
            } else if (!(infopoint.getClass() == Infopoint.class)) {
                reject("ip-specialized-subtype");
                return false;
            }
        }
        /*
         * getMethods() is null unless the compilation recorded method dependencies, which is the
         * common case; anything longer than the root method indicates inlining, which is not
         * reconstructed by this cache.
         */
        ResolvedJavaMethod[] methods = result.getMethods();
        if (methods != null && methods.length > 1) {
            reject("inlinedMethods");
            return false;
        }
        /*
         * Marks are fully reconstructed from the serialized (pcOffset, SubstrateMarkId) pairs; a
         * non-enum MarkId cannot be rebuilt, so such results are not cacheable.
         */
        for (CompilationResult.CodeMark mark : result.getMarks()) {
            if (!(mark.id instanceof SubstrateBackend.SubstrateMarkId)) {
                reject("marks-foreign-id");
                return false;
            }
        }
        for (CompilationResult.CodeAnnotation annotation : result.getCodeAnnotations()) {
            if (annotation instanceof CompilationResult.CodeComment) {
                /* Disassembler comments only; they do not affect the image. */
                continue;
            }
            /*
             * Only the aarch64 single-instruction patcher (direct B/BL call sites) is restored by
             * this cache; everything else (jump tables, cglobal refs, mov sequences) is rejected.
             */
            if (annotation instanceof CompilationResult.CodeComment) {
                continue;
            }
            if ((SubstrateTarget.getArchitecture() instanceof AArch64) && AArch64HostedPatcherFeature.extractSingleInstruction(annotation) != null) {
                continue;
            }
            if (!conservative && annotation instanceof HostedImageHeapConstantPatch) {
                continue;
            }
            if (!conservative && (SubstrateTarget.getArchitecture() instanceof AArch64) && AArch64HostedPatcherFeature.extractMovSequence(annotation) != null) {
                continue;
            }
            reject((hasDataPatches ? "tlab-annotation:" : "annotation:") + ClassUtil.getUnqualifiedName(annotation.getClass()));
            return false;
        }
        int dataItemCount = 0;
        for (DataSection.Data ignored : result.getDataSection()) {
            dataItemCount++;
        }
        if (dataItemCount != 0) {
            reject("dataSection");
            return false;
        }
        boolean hasCall = result.getInfopoints().stream().anyMatch(infopoint -> infopoint instanceof Call);
        accept(hasCall ? "withCall" : "noCall");
        return true;
    }

    static CompilationResult tryLoad(HostedMethod method, CompilationIdentifier compilationIdentifier, String fingerprint, String cacheDir,
                    Function<String, HostedMethod> methodKeyResolver, Function<String, com.oracle.graal.pointsto.meta.AnalysisType> typeResolver) {
        if (!fingerprintIndex(cacheDir).contains(fingerprint)) {
            return null;
        }
        Path file = Path.of(cacheDir).resolve(fingerprint + CACHE_FILE_SUFFIX);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return null;
            }
            if (!fingerprint.equals(in.readUTF())) {
                return null;
            }
            /* Method key is informational only. */
            in.readUTF();
            int codeSize = in.readInt();
            byte[] code = new byte[codeSize];
            in.readFully(code);
            int totalFrameSize = in.readInt();
            int frameSize = in.readInt();
            int framePointerSaveAreaOffset = in.readInt();
            int codeAlignment = in.readInt();

            SharedCompilationResult result = new SharedCompilationResult(compilationIdentifier, method.getQualifiedName()) {
                @Override
                public void close(OptionValues options) {
                    /*
                     * Same as SubstrateBackend.newCompilationResult: data items and machine code
                     * are aggregated in the native image heap, there is nothing to close.
                     */
                }
            };
            result.setTargetCode(code, codeSize);
            result.setTotalFrameSize(totalFrameSize);
            if (frameSize != -1) {
                result.setFrameSize(frameSize);
            }
            if (framePointerSaveAreaOffset != -1) {
                result.setFramePointerSaveAreaOffset(framePointerSaveAreaOffset);
            }
            result.setCodeAlignment(codeAlignment);

            int markCount = in.readInt();
            for (int i = 0; i < markCount; i++) {
                int markPcOffset = in.readInt();
                String markName = in.readUTF();
                result.recordMark(markPcOffset, SubstrateBackend.SubstrateMarkId.valueOf(markName));
            }
            int patcherCount = in.readInt();
            for (int i = 0; i < patcherCount; i++) {
                int position = in.readInt();
                byte patcherKind = in.readByte();
                switch (patcherKind) {
                    case 1:
                        result.addAnnotation(new HostedImageHeapConstantPatch(position, readImageHeapConstant(in, typeResolver)));
                        break;
                    case 2:
                        result.addAnnotation(new CachedSingleInstructionPatcher(position, in.readInt(), in.readInt(), in.readInt()));
                        break;
                    case 3: {
                        int actionCount = in.readInt();
                        AArch64MacroAssembler.MovSequenceAnnotation.MovAction[] includeSet = new AArch64MacroAssembler.MovSequenceAnnotation.MovAction[actionCount];
                        for (int a = 0; a < actionCount; a++) {
                            includeSet[a] = AArch64MacroAssembler.MovSequenceAnnotation.MovAction.valueOf(in.readUTF());
                        }
                        result.addAnnotation(new CachedMovSequencePatcher(position, includeSet));
                        break;
                    }
                    default:
                        return null;
                }
            }
            int exceptionHandlerCount = in.readInt();
            for (int i = 0; i < exceptionHandlerCount; i++) {
                result.recordExceptionHandler(in.readInt(), in.readInt());
            }
            int dataPatchCount = in.readInt();
            for (int i = 0; i < dataPatchCount; i++) {
                result.recordDataPatch(in.readInt(), new ConstantReference(readImageHeapConstant(in, typeResolver)));
            }
            int infopointCount = in.readInt();
            for (int i = 0; i < infopointCount; i++) {
                int pcOffset = in.readInt();
                InfopointReason reason = InfopointReason.valueOf(in.readUTF());
                boolean isCall = in.readBoolean();
                boolean direct = false;
                int size = 0;
                HostedMethod callTarget = null;
                if (isCall) {
                    direct = in.readBoolean();
                    size = in.readInt();
                    String targetKey = in.readUTF();
                    callTarget = methodKeyResolver.apply(targetKey);
                    if (callTarget == null) {
                        return null;
                    }
                }
                DebugInfo debugInfo = null;
                if (in.readBoolean()) {
                    debugInfo = readDebugInfo(in, methodKeyResolver);
                    if (debugInfo == null) {
                        return null;
                    }
                }
                if (isCall) {
                    result.recordCall(pcOffset, size, callTarget, debugInfo, direct);
                } else {
                    result.addInfopoint(new Infopoint(pcOffset, debugInfo, reason));
                }
            }
            /* No setMethods: keep the same null methods array a fresh compilation would have. */
            return result;
        } catch (IOException | RuntimeException | StackOverflowError ex) {
            LogUtils.warning("Ignoring unreadable compilation result cache entry %s: %s", file, ex);
            return null;
        }
    }

    static boolean store(HostedMethod method, CompilationResult result, String fingerprint, String cacheDir) {
        try {
            Path dir = Path.of(cacheDir);
            Files.createDirectories(dir);
            int codeSize = result.getTargetCodeSize();
            Path tmpFile = dir.resolve(fingerprint + "-" + Long.toHexString(Thread.currentThread().getId()) + ".tmp");
            Path dstFile = dir.resolve(fingerprint + CACHE_FILE_SUFFIX);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpFile)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(fingerprint);
                out.writeUTF(method.format("%H.%n(%p) %r"));
                out.writeInt(codeSize);
                out.write(result.getTargetCode(), 0, codeSize);
                out.writeInt(result.getTotalFrameSize());
                out.writeInt(frameSizeOf(result));
                out.writeInt(framePointerSaveAreaOffsetOf(result));
                out.writeInt(SharedCompilationResult.getCodeAlignment(result));
                out.writeInt(result.getMarks().size());
                for (CompilationResult.CodeMark mark : result.getMarks()) {
                    out.writeInt(mark.pcOffset);
                    out.writeUTF(((SubstrateBackend.SubstrateMarkId) mark.id).name());
                }
                int patcherCount = 0;
                for (CompilationResult.CodeAnnotation annotation : result.getCodeAnnotations()) {
                    if (!(annotation instanceof CompilationResult.CodeComment)) {
                        patcherCount++;
                    }
                }
                out.writeInt(patcherCount);
                for (CompilationResult.CodeAnnotation annotation : result.getCodeAnnotations()) {
                    if (annotation instanceof CompilationResult.CodeComment) {
                        continue;
                    }
                    if (annotation instanceof HostedImageHeapConstantPatch heapPatch) {
                        out.writeInt(heapPatch.getPosition());
                        out.writeByte(1);
                        writeImageHeapConstant(out, (ImageHeapInstance) heapPatch.constant);
                        continue;
                    }
                    SingleInstructionAnnotation singleInstruction = AArch64HostedPatcherFeature.extractSingleInstruction(annotation);
                    if (singleInstruction != null) {
                        out.writeInt(singleInstruction.instructionPosition);
                        out.writeByte(2);
                        out.writeInt(singleInstruction.operandSizeBits);
                        out.writeInt(singleInstruction.offsetBits);
                        out.writeInt(singleInstruction.shift);
                        continue;
                    }
                    AArch64MacroAssembler.MovSequenceAnnotation movSequence = AArch64HostedPatcherFeature.extractMovSequence(annotation);
                    out.writeInt(movSequence.instructionPosition);
                    out.writeByte(3);
                    out.writeInt(movSequence.includeSet.length);
                    for (AArch64MacroAssembler.MovSequenceAnnotation.MovAction action : movSequence.includeSet) {
                        out.writeUTF(action.name());
                    }
                }
                out.writeInt(result.getExceptionHandlers().size());
                for (ExceptionHandler handler : result.getExceptionHandlers()) {
                    out.writeInt(handler.pcOffset);
                    out.writeInt(handler.handlerPos);
                }
                out.writeInt(result.getDataPatches().size());
                for (DataPatch dataPatch : result.getDataPatches()) {
                    out.writeInt(dataPatch.pcOffset);
                    writeImageHeapConstant(out, (ImageHeapInstance) ((ConstantReference) dataPatch.reference).getConstant());
                }
                out.writeInt(result.getInfopoints().size());
                for (Infopoint infopoint : result.getInfopoints()) {
                    out.writeInt(infopoint.pcOffset);
                    out.writeUTF(infopoint.reason.name());
                    if (infopoint instanceof Call call) {
                        out.writeBoolean(true);
                        out.writeBoolean(call.direct);
                        out.writeInt(call.size);
                        out.writeUTF(CompileQueue.methodCacheKey((HostedMethod) call.target));
                    } else {
                        out.writeBoolean(false);
                    }
                    out.writeBoolean(infopoint.debugInfo != null);
                    if (infopoint.debugInfo != null) {
                        writeDebugInfo(out, infopoint.debugInfo);
                    }
                }
            }
            Files.move(tmpFile, dstFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            fingerprintIndex(cacheDir).add(fingerprint);
            appendToIndex(dir, fingerprint, codeSize, method);
            return true;
        } catch (IOException | RuntimeException | StackOverflowError ex) {
            LogUtils.warning("Cannot write compilation result cache entry for %s: %s", method.format("%H.%n(%p)"), ex);
            return false;
        }
    }

    private static Set<String> fingerprintIndex(String cacheDir) {
        return FINGERPRINT_INDEX.computeIfAbsent(cacheDir, dir -> {
            Set<String> index = ConcurrentHashMap.newKeySet();
            File[] files = new File(dir).listFiles((d, name) -> name.endsWith(CACHE_FILE_SUFFIX));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    index.add(name.substring(0, name.length() - CACHE_FILE_SUFFIX.length()));
                }
            }
            return index;
        });
    }

    private static int frameSizeOf(CompilationResult result) {
        if (result instanceof SharedCompilationResult shared) {
            try {
                return shared.getFrameSize();
            } catch (AssertionError ex) {
                /* Frame size was never set; represented as -1 in the cache. */
            }
        }
        return -1;
    }

    private static int framePointerSaveAreaOffsetOf(CompilationResult result) {
        if (result instanceof SharedCompilationResult shared && shared.hasFramePointerSaveAreaOffset()) {
            return shared.getFramePointerSaveAreaOffset();
        }
        return -1;
    }

    private static void appendToIndex(Path dir, String fingerprint, int codeSize, HostedMethod method) throws IOException {
        synchronized (CompilationResultStore.class) {
            try (FileWriter writer = new FileWriter(dir.resolve(INDEX_FILE_NAME).toFile(), StandardCharsets.UTF_8, true)) {
                writer.write(fingerprint + "\t" + codeSize + "\t" + method.format("%H.%n(%p) %r") + System.lineSeparator());
            }
        }
    }

    /**
     * Rebuilt equivalent of the aarch64 {@code SingleInstructionHostedPatcher} for direct B/BL
     * call sites. The encoding logic mirrors {@code SingleInstructionAnnotation.patch}, delegating
     * the instruction bit splicing to {@link PatcherUtil}.
     */
    private static final class CachedSingleInstructionPatcher extends CompilationResult.CodeAnnotation implements HostedPatcher {

        private final int operandSizeBits;
        private final int offsetBits;
        private final int shift;

        CachedSingleInstructionPatcher(int position, int operandSizeBits, int offsetBits, int shift) {
            super(position);
            this.operandSizeBits = operandSizeBits;
            this.offsetBits = offsetBits;
            this.shift = shift;
        }

        @Override
        public void relocate(Reference ref, RelocatableBuffer relocs, int compStart) {
            throw VMError.shouldNotReachHere("relocate is not supported for cached direct call sites");
        }

        @Override
        public void patch(int compStart, int relative, byte[] code) {
            int curValue = relative;
            assert (curValue & ((1 << shift) - 1)) == 0 : "relative offset has incorrect alignment";
            curValue = curValue >> shift;
            GraalError.guarantee(NumUtil.isSignedNbit(operandSizeBits, curValue), "value too large to fit into space");

            int bitsRemaining = operandSizeBits;
            int offsetRemaining = offsetBits;
            int[] bitsUsed = new int[4];
            int[] offsets = new int[4];
            for (int i = 0; i < 4; ++i) {
                if (offsetRemaining >= 8) {
                    offsetRemaining -= 8;
                    continue;
                }
                offsets[i] = offsetRemaining;
                int bits = Math.min(8 - offsetRemaining, bitsRemaining);
                bitsUsed[i] = bits;
                bitsRemaining -= bits;
                offsetRemaining = 0;
            }
            int originalInstruction = PatcherUtil.readInstruction(code, getPosition());
            int newInstruction = PatcherUtil.patchBitSequence(originalInstruction, curValue, bitsUsed, offsets);
            PatcherUtil.writeInstruction(code, getPosition(), newInstruction);
        }

        @Override
        public boolean equals(Object obj) {
            return obj == this;
        }
    }

    /**
     * Rebuilt equivalent of the aarch64 {@code MovSequenceHostedPatcher} for heap-constant mov
     * sequences. The relocation logic mirrors {@code MovSequenceHostedPatcher.relocate}; patching
     * is not allowed, same as the original.
     */
    private static final class CachedMovSequencePatcher extends CompilationResult.CodeAnnotation implements HostedPatcher {

        private final AArch64MacroAssembler.MovSequenceAnnotation.MovAction[] includeSet;

        CachedMovSequencePatcher(int position, AArch64MacroAssembler.MovSequenceAnnotation.MovAction[] includeSet) {
            super(position);
            this.includeSet = includeSet;
        }

        @Override
        public void relocate(Reference ref, RelocatableBuffer relocs, int compStart) {
            int siteOffset = compStart + getPosition();
            if (ref instanceof DataSectionReference || ref instanceof CGlobalDataDirectReference || ref instanceof ConstantReference) {
                if (ref instanceof ConstantReference constantRef) {
                    VMError.guarantee(!(constantRef.getConstant() instanceof SubstrateMethodPointerConstant), "SubstrateMethodPointerConstants should not be relocated %s", constantRef);
                }
                int lastMovIndex = -1;
                for (int i = 0; i < includeSet.length; i++) {
                    if (includeSet[i] == AArch64MacroAssembler.MovSequenceAnnotation.MovAction.USED) {
                        lastMovIndex = i;
                    }
                }
                ObjectFile.RelocationKind[] relocations = {ObjectFile.RelocationKind.AARCH64_R_MOVW_UABS_G0, ObjectFile.RelocationKind.AARCH64_R_MOVW_UABS_G1,
                                ObjectFile.RelocationKind.AARCH64_R_MOVW_UABS_G2, ObjectFile.RelocationKind.AARCH64_R_MOVW_UABS_G3};
                ObjectFile.RelocationKind[] noCheckRelocations = {ObjectFile.RelocationKind.AARCH64_R_MOVW_UABS_G0_NC, ObjectFile.RelocationKind.AARCH64_R_MOVW_UABS_G1_NC,
                                ObjectFile.RelocationKind.AARCH64_R_MOVW_UABS_G2_NC};
                for (int i = 0; i < includeSet.length; i++) {
                    if (includeSet[i] == AArch64MacroAssembler.MovSequenceAnnotation.MovAction.SKIPPED) {
                        continue;
                    }
                    if (i == lastMovIndex) {
                        relocs.addRelocationWithoutAddend(siteOffset, relocations[i], ref);
                    } else {
                        relocs.addRelocationWithoutAddend(siteOffset, noCheckRelocations[i], ref);
                    }
                    siteOffset = siteOffset + 4;
                }
            } else {
                throw VMError.shouldNotReachHere("Unknown type of reference in code");
            }
        }

        @Override
        public void patch(int compStart, int relative, byte[] code) {
            throw VMError.shouldNotReachHere("patching not allowed");
        }

        @Override
        public boolean equals(Object obj) {
            return obj == this;
        }
    }
}
