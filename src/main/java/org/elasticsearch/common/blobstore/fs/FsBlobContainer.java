/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.blobstore.fs;

import com.google.common.collect.ImmutableMap;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.common.blobstore.BlobMetaData;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.support.AbstractBlobContainer;
import org.elasticsearch.common.blobstore.support.PlainBlobMetaData;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.io.FileSystemUtils;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 *
 */
public class FsBlobContainer extends AbstractBlobContainer {

    protected final FsBlobStore blobStore;

    protected final Path path;

    public FsBlobContainer(FsBlobStore blobStore, BlobPath blobPath, Path path) {
        super(blobPath);
        this.blobStore = blobStore;
        this.path = path;
    }

    public ImmutableMap<String, BlobMetaData> listBlobs() throws IOException {
        Path[] files = FileSystemUtils.files(path);
        if (files.length == 0) {
            return ImmutableMap.of();
        }
        // using MapBuilder and not ImmutableMap.Builder as it seems like File#listFiles might return duplicate files!
        MapBuilder<String, BlobMetaData> builder = MapBuilder.newMapBuilder();
        for (Path file : files) {
            final BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            if (attrs.isRegularFile()) {
                builder.put(file.getFileName().toString(), new PlainBlobMetaData(file.getFileName().toString(), attrs.size()));
            }
        }
        return builder.immutableMap();
    }

    @Override
    public void deleteBlob(String blobName) throws IOException {
        Path blobPath = path.resolve(blobName);
        Files.deleteIfExists(blobPath);
    }

    @Override
    public boolean blobExists(String blobName) {
        return Files.exists(path.resolve(blobName));
    }

    @Override
    public InputStream openInput(String name) throws IOException {
        return new BufferedInputStream(Files.newInputStream(path.resolve(name)), blobStore.bufferSizeInBytes());
    }

    @Override
    public OutputStream createOutput(String blobName) throws IOException {
        final Path file = path.resolve(blobName);
        return new BufferedOutputStream(new FilterOutputStream(Files.newOutputStream(file)) {

            @Override // FilterOutputStream#write(byte[] b, int off, int len) is trappy writes every single byte
            public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len);}

            @Override
            public void close() throws IOException {
                super.close();
                IOUtils.fsync(file, false);
                IOUtils.fsync(path, true);
            }
        }, blobStore.bufferSizeInBytes());
    }

    @Override
    public void move(String source, String target) throws IOException {
        Path sourcePath = path.resolve(source);
        Path targetPath = path.resolve(target);
        // If the target file exists then Files.move() behaviour is implementation specific
        // the existing file might be replaced or this method fails by throwing an IOException.
        assert !Files.exists(targetPath);
        Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE);
        IOUtils.fsync(path, true);
    }
}
