/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.bkd;

import java.io.IOException;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;

/** Writes points to disk in a fixed-with format. */
final class OfflinePointWriter implements PointWriter {

  final Directory tempDir;
  final IndexOutput out;
  final int packedBytesLength;
  private long count;
  private boolean closed;

  public OfflinePointWriter(Directory tempDir, String tempFileNamePrefix, int packedBytesLength, String desc) throws IOException {
    this.out = tempDir.createTempOutput(tempFileNamePrefix, "bkd_" + desc, IOContext.DEFAULT);
    this.tempDir = tempDir;
    this.packedBytesLength = packedBytesLength;
  }

  /** Initializes on an already written/closed file, just so consumers can use {@link #getReader} to read the file. */
  public OfflinePointWriter(Directory tempDir, IndexOutput out, int packedBytesLength, long count) {
    this.out = out;
    this.tempDir = tempDir;
    this.packedBytesLength = packedBytesLength;
    this.count = count;
    closed = true;
  }
    
  @Override
  public void append(byte[] packedValue, long ord, int docID) throws IOException {
    assert packedValue.length == packedBytesLength;
    out.writeBytes(packedValue, 0, packedValue.length);
    out.writeInt(docID);
    out.writeLong(ord);
    count++;
  }

  @Override
  public PointReader getReader(long start, long length) throws IOException {
    assert closed;
    assert start + length <= count: "start=" + start + " length=" + length + " count=" + count;
    return new OfflinePointReader(tempDir, out.getName(), packedBytesLength, start, length);
  }

  @Override
  public void close() throws IOException {
    if (closed == false) {
      try {
        CodecUtil.writeFooter(out);
      } finally {
        out.close();
        closed = true;
      }
    }
  }

  @Override
  public void destroy() throws IOException {
    tempDir.deleteFile(out.getName());
  }

  @Override
  public String toString() {
    return "OfflinePointWriter(count=" + count + " tempFileName=" + out.getName() + ")";
  }
}
