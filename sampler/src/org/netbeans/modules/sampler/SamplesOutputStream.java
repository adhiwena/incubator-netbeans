/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.sampler;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.management.ThreadInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.zip.GZIPOutputStream;
import javax.management.openmbean.CompositeData;

/**
 *
 * @author Tomas Hurka
 */
class SamplesOutputStream {

    private static final String[][] methods = new String[][]{
        {"sun.management.ThreadInfoCompositeData", "toCompositeData"}, // NOI18N Sun JVM
        {"com.ibm.lang.management.ManagementUtils", "toThreadInfoCompositeData"} // NOI18N IBM J9
    };
    static final String ID = "NPSS"; // NetBeans Profiler samples stream
    public static final String FILE_EXT = ".npss"; // NOI18N
    static final int RESET_THRESHOLD = 5000;
    static final int STEPS = 1000;
    static byte version = 2;
    private static Method toCompositeDataMethod;

    static {
        for (String[] method : methods) {
            String className = method[0];
            String methodName = method[1];
            try {
                Class clazz = Class.forName(className);
                toCompositeDataMethod = clazz.getMethod(methodName, ThreadInfo.class);
                if (toCompositeDataMethod != null) {
                    break;
                }
            } catch (ClassNotFoundException ex) {
            } catch (NoSuchMethodException ex) {
            } catch (SecurityException ex) {
            }
        }
    }
    OutputStream outStream;
    Map<Long, ThreadInfo> lastThreadInfos;
    Map<StackTraceElement, WeakReference<StackTraceElement>> steCache;
    List<Sample> samples;
    Sampler progress;
    int maxSamples;
    int offset;

    public static boolean isSupported() {
        return toCompositeDataMethod != null;
    }

    SamplesOutputStream(OutputStream os, Sampler progress, int max) throws IOException {
        maxSamples = max;
        this.progress = progress;
        outStream = os;
        writeHeader(os);
//        out = new ObjectOutputStream(os);
        lastThreadInfos = new HashMap();
        steCache = new WeakHashMap(8*1024);
        samples = new ArrayList(1024);
    }

    void writeSample(ThreadInfo[] infos, long time, long selfThreadId) throws IOException {
        List<Long> sameT = new ArrayList();
        List<ThreadInfo> newT = new ArrayList();
        List<Long> tids = new ArrayList();

        for (ThreadInfo tinfo : infos) {
            long id;

            if (tinfo == null) continue;    // ignore null ThreadInfo
            id = tinfo.getThreadId();
            if (id != selfThreadId) { // ignore sampling thread
                Long tid = Long.valueOf(tinfo.getThreadId());
                ThreadInfo lastThread = lastThreadInfos.get(tid);

                tids.add(tid);
                if (lastThread != null) {
                    if (lastThread.getThreadState().equals(tinfo.getThreadState())) {
                        StackTraceElement[] lastStack = lastThread.getStackTrace();
                        StackTraceElement[] stack = tinfo.getStackTrace();

                        if (Arrays.deepEquals(lastStack, stack)) {
                            sameT.add(tid);
                            continue;
                        }
                    }
                }
                internStackTrace(tinfo);
                newT.add(tinfo);
                lastThreadInfos.put(tid, tinfo);
            }
        }
        addSample(new Sample(time, sameT, newT));
        // remove dead threads
        Set<Long> ids = new HashSet(lastThreadInfos.keySet());
        ids.removeAll(tids);
        lastThreadInfos.keySet().removeAll(ids);
    }

    private void addSample(Sample sample) {
        if (samples.size() == maxSamples) {
            Sample lastSample;
            Sample removedSample = samples.set(offset, sample);
            offset = (offset + 1) % maxSamples;
            lastSample = samples.get(offset);
            updateLastSample(removedSample,lastSample);
        } else {
            samples.add(sample);
        }
    }
    
    Sample getSample(int index) {
        int arrayIndex = index;
        if (samples.size() == maxSamples) {
            arrayIndex = (offset + index) % maxSamples;
        }
        return samples.get(arrayIndex);
    }

    void removeSample(int index) {
        int arrayIndex = index;
        if (samples.size() == maxSamples) {
            arrayIndex = (offset + index) % maxSamples;
        }
        samples.set(arrayIndex,null);
    }
    
    private void updateLastSample(Sample removedSample, Sample lastSample) {
        List<ThreadInfo> removedNewThreads = removedSample.getNewThreads();
        List<Long> sameThreads = lastSample.getSameThread();
        List<ThreadInfo> newThreads = lastSample.getNewThreads();
        
        for (ThreadInfo ti : removedNewThreads) {
            Long tid = Long.valueOf(ti.getThreadId());
            if (sameThreads.contains(tid)) {
                newThreads.add(ti);
                sameThreads.remove(tid);
            }
        }
    }

    private static CompositeData toCompositeData(ThreadInfo tinfo) {
        try {
            return (CompositeData) toCompositeDataMethod.invoke(null, tinfo);
        } catch (IllegalAccessException ex) {
            throw new RuntimeException(ex);
        } catch (IllegalArgumentException ex) {
            throw new RuntimeException(ex);
        } catch (InvocationTargetException ex) {
            throw new RuntimeException(ex);
        }
    }

    void close() throws IOException {
        steCache = null;
        GZIPOutputStream stream = new GZIPOutputStream(outStream, 64 * 1024);
        ObjectOutputStream out = new ObjectOutputStream(stream);
        int size = samples.size();
        out.writeInt(size);
        out.writeLong(getSample(size-1).getTime());
        openProgress();
        for (int i=0; i<size;i++) {
            Sample s = getSample(i);
            removeSample(i);
            if (i > 0 && i % RESET_THRESHOLD == 0) {
                out.reset();
            }
            s.writeToStream(out);
            if ((i+40) % 50 == 0) step((STEPS*i)/size);
        }
        step(STEPS); // set progress at 100%
        out.close();
        closeProgress();
    }

    private void writeHeader(OutputStream os) throws IOException {
        os.write(ID.getBytes());
        os.write(version);
    }

    private void internStackTrace(ThreadInfo tinfo) {
        if (steCache == null) {
            return;
        }

        StackTraceElement[] stack = tinfo.getStackTrace();

        for (int i = 0; i < stack.length; i++) {
            StackTraceElement ste = stack[i];
            WeakReference<StackTraceElement> oldStackRef = steCache.get(ste);

            if (oldStackRef != null) {
                stack[i] = oldStackRef.get();
                assert stack[i] != null;
            } else {
                steCache.put(ste, new WeakReference(ste));
            }
        }
    }

    private void openProgress() {
        if (progress != null) {
            progress.openProgress(STEPS);
        }
    }

    private void closeProgress() {
        if (progress != null) {
            progress.closeProgress();
        }
    }

    private void step(int i) {
        if (progress != null) {
            progress.progress(i);
        }
    }

    private static class Sample {

        final private long time;
        final private List<Long> sameThread;
        final private List<ThreadInfo> newThreads;

        Sample(long t, List<Long> sameT, List<ThreadInfo> newT) {
            time = t;
            sameThread = sameT;
            newThreads = newT;
        }

        private long getTime() {
            return time;
        }

        private List<Long> getSameThread() {
            return sameThread;
        }

        private List<ThreadInfo> getNewThreads() {
            return newThreads;
        }

        private void writeToStream(ObjectOutputStream out) throws IOException {
            out.writeLong(time);
            out.writeInt(sameThread.size());
            for (Long tid : sameThread) {
                out.writeLong(tid.longValue());
            }
            out.writeInt(newThreads.size());
            for (ThreadInfo tic : newThreads) {
                out.writeObject(toCompositeData(tic));
            }
        }
    }
}
