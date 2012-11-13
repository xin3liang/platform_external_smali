/*
 * Copyright 2012, Google Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jf.dexlib2.dexbacked.util;

import com.google.common.collect.Iterators;
import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.DebugItemType;
import org.jf.dexlib2.base.BaseMethodParameter;
import org.jf.dexlib2.dexbacked.DexBackedMethod;
import org.jf.dexlib2.dexbacked.DexBackedMethodImplementation;
import org.jf.dexlib2.dexbacked.DexBuffer;
import org.jf.dexlib2.dexbacked.DexReader;
import org.jf.dexlib2.iface.Annotation;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.iface.debug.DebugItem;
import org.jf.dexlib2.iface.debug.EndLocal;
import org.jf.dexlib2.iface.debug.LocalInfo;
import org.jf.dexlib2.immutable.debug.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public abstract class DebugInfo implements Iterable<DebugItem> {
    @Nonnull public abstract Collection<? extends MethodParameter> getParametersWithNames();

    public static DebugInfo newOrEmpty(@Nonnull DexBuffer dexBuf, int debugInfoOffset,
                                       @Nonnull DexBackedMethodImplementation methodImpl) {
        if (debugInfoOffset == 0) {
            new EmptyDebugInfo(methodImpl.method);
        }
        return new DebugInfoImpl(dexBuf, debugInfoOffset, methodImpl);
    }

    private static class EmptyDebugInfo extends DebugInfo {
        @Nonnull private final DexBackedMethod method;
        public EmptyDebugInfo(@Nonnull DexBackedMethod method) { this.method = method; }
        @Nonnull @Override public Iterator<DebugItem> iterator() { return Iterators.emptyIterator(); }
        @Nonnull @Override public List<? extends MethodParameter> getParametersWithNames() {
            return method.getParametersWithoutNames();
        }
    }

    private static class DebugInfoImpl extends DebugInfo {
        @Nonnull public final DexBuffer dexBuf;
        private final int debugInfoOffset;
        @Nonnull private final DexBackedMethodImplementation methodImpl;

        public DebugInfoImpl(@Nonnull DexBuffer dexBuf,
                         int debugInfoOffset,
                         @Nonnull DexBackedMethodImplementation methodImpl) {
            this.dexBuf = dexBuf;
            this.debugInfoOffset = debugInfoOffset;
            this.methodImpl = methodImpl;
        }

        private static final LocalInfo EMPTY_LOCAL_INFO = new LocalInfo() {
            @Nullable @Override public String getName() { return null; }
            @Nullable @Override public String getType() { return null; }
            @Nullable @Override public String getSignature() { return null; }
        };

        @Nonnull
        @Override
        public Iterator<DebugItem> iterator() {
            DexReader initialReader = dexBuf.readerAt(debugInfoOffset);
            // TODO: this unsigned value could legitimally be > MAX_INT
            final int lineNumberStart = initialReader.readSmallUleb128();
            int registerCount = methodImpl.getRegisterCount();

            //TODO: does dalvik allow references to invalid registers?
            final LocalInfo[] locals = new LocalInfo[registerCount];
            Arrays.fill(locals, EMPTY_LOCAL_INFO);

            final VariableSizeIterator<? extends MethodParameter> parameterIterator =
                    getParametersWithNames().iterator();

            // first, we grab all the parameters and temporarily store them at the beginning of locals,
            // disregarding any wide types
            int parameterIndex = 0;
            if (!AccessFlags.STATIC.isSet(methodImpl.method.getAccessFlags())) {
                // add the local info for the "this" parameter
                locals[parameterIndex++] = new LocalInfo() {
                    @Override public String getName() { return "this"; }
                    @Override public String getType() { return methodImpl.method.classDef.getType(); }
                    @Override public String getSignature() { return null; }
                };
            }
            while (parameterIterator.hasNext()) {
                locals[parameterIndex++] = parameterIterator.next();
            }

            if (parameterIndex < registerCount) {
                // now, we push the parameter locals back to their appropriate register, starting from the end
                int localIndex = registerCount-1;
                while(--parameterIndex > -1) {
                    LocalInfo currentLocal = locals[parameterIndex];
                    String type = currentLocal.getType();
                    if (type != null && (type.equals("J") || type.equals("D"))) {
                        localIndex--;
                        if (localIndex == parameterIndex) {
                            // there's no more room to push, the remaining registers are already in the correct place
                            break;
                        }
                    }
                    locals[localIndex] = currentLocal;
                    locals[parameterIndex] = EMPTY_LOCAL_INFO;
                    localIndex--;
                }
            }

            return new VariableSizeLookaheadIterator<DebugItem>(dexBuf, parameterIterator.getReaderOffset()) {
                private int codeAddress = 0;
                private int lineNumber = lineNumberStart;

                @Nullable
                protected DebugItem readNextItem(@Nonnull DexReader reader) {
                    while (true) {
                        int next = reader.readUbyte();
                        switch (next) {
                            case DebugItemType.END_SEQUENCE: {
                                return null;
                            }
                            case DebugItemType.ADVANCE_PC: {
                                int addressDiff = reader.readSmallUleb128();
                                codeAddress += addressDiff;
                                continue;
                            }
                            case DebugItemType.ADVANCE_LINE: {
                                int lineDiff = reader.readSleb128();
                                lineNumber += lineDiff;
                                continue;
                            }
                            case DebugItemType.START_LOCAL: {
                                int register = reader.readSmallUleb128();
                                String name = dexBuf.getOptionalString(reader.readSmallUleb128() - 1);
                                String type = dexBuf.getOptionalType(reader.readSmallUleb128() - 1);
                                ImmutableStartLocal startLocal =
                                        new ImmutableStartLocal(codeAddress, register, name, type, null);
                                locals[register] = startLocal;
                                return startLocal;
                            }
                            case DebugItemType.START_LOCAL_EXTENDED: {
                                int register = reader.readSmallUleb128();
                                String name = dexBuf.getOptionalString(reader.readSmallUleb128() - 1);
                                String type = dexBuf.getOptionalType(reader.readSmallUleb128() - 1);
                                String signature = dexBuf.getOptionalString(reader.readSmallUleb128() - 1);
                                ImmutableStartLocal startLocal =
                                        new ImmutableStartLocal(codeAddress, register, name, type, signature);
                                locals[register] = startLocal;
                                return startLocal;
                            }
                            case DebugItemType.END_LOCAL: {
                                int register = reader.readSmallUleb128();
                                LocalInfo localInfo = locals[register];
                                boolean replaceLocalInTable = true;
                                if (localInfo instanceof EndLocal) {
                                    localInfo = EMPTY_LOCAL_INFO;
                                    // don't replace the local info in locals. The new EndLocal won't have any info at all,
                                    // and we dont want to wipe out what's there, so that it is available for a subsequent
                                    // RestartLocal
                                    replaceLocalInTable = false;
                                }
                                ImmutableEndLocal endLocal =
                                        new ImmutableEndLocal(codeAddress, register, localInfo.getName(),
                                                localInfo.getType(), localInfo.getSignature());
                                if (replaceLocalInTable) {
                                    locals[register] = endLocal;
                                }
                                return endLocal;
                            }
                            case DebugItemType.RESTART_LOCAL: {
                                int register = reader.readSmallUleb128();
                                LocalInfo localInfo = locals[register];
                                ImmutableRestartLocal restartLocal =
                                        new ImmutableRestartLocal(codeAddress, register, localInfo.getName(),
                                                localInfo.getType(), localInfo.getSignature());
                                locals[register] = restartLocal;
                                return restartLocal;
                            }
                            case DebugItemType.PROLOGUE_END: {
                                return new ImmutablePrologueEnd(codeAddress);
                            }
                            case DebugItemType.EPILOGUE_BEGIN: {
                                return new ImmutableEpilogueBegin(codeAddress);
                            }
                            case DebugItemType.SET_SOURCE_FILE: {
                                String sourceFile = dexBuf.getOptionalString(reader.readSmallUleb128() - 1);
                                return new ImmutableSetSourceFile(codeAddress, sourceFile);
                            }
                            default: {
                                int adjusted = next - 0x0A;
                                codeAddress += adjusted / 15;
                                lineNumber += (adjusted % 15) - 4;
                                return new ImmutableLineNumber(codeAddress, lineNumber);
                            }
                        }
                    }
                }
            };
        }

        @Nonnull
        @Override
        public VariableSizeCollection<MethodParameter> getParametersWithNames() {
            DexReader reader = dexBuf.readerAt(debugInfoOffset);
            reader.skipUleb128();
            final int parameterNameCount = reader.readSmallUleb128();
            final List<? extends MethodParameter> methodParametersWithoutNames =
                    methodImpl.method.getParametersWithoutNames();
            //TODO: make sure dalvik doesn't allow more parameter names than we have parameters

            return new VariableSizeCollection<MethodParameter>(dexBuf, reader.getOffset(),
                                                               methodParametersWithoutNames.size()) {
                @Nonnull
                @Override
                protected MethodParameter readNextItem(@Nonnull DexReader reader, int index) {
                    final MethodParameter methodParameter = methodParametersWithoutNames.get(index);
                    String _name = null;
                    if (index < parameterNameCount) {
                        _name = reader.getOptionalString(reader.readSmallUleb128() - 1);
                    }
                    final String name = _name;

                    return new BaseMethodParameter() {
                        @Nonnull @Override public String getType() { return methodParameter.getType(); }
                        @Nullable @Override public String getName() { return name; }
                        @Nullable @Override public String getSignature() { return methodParameter.getSignature();}
                        @Nonnull @Override public Collection<? extends Annotation> getAnnotations() {
                            return methodParameter.getAnnotations();
                        }
                    };
                }
            };
        }
    }
}
