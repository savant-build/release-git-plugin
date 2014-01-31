#!/bin/bash
mkdir -p ~/.savant/cache/org/savantbuild/plugin/file/0.1.0-\{integration\}/
cp build/jars/*.jar ~/.savant/cache/org/savantbuild/plugin/file/0.1.0-\{integration\}/
cp src/main/resources/amd.xml ~/.savant/cache/org/savantbuild/plugin/file/0.1.0-\{integration\}/file-0.1.0-\{integration\}.jar.amd
cd ~/.savant/cache/org/savantbuild/plugin/file/0.1.0-\{integration\}/
md5sum file-0.1.0-\{integration\}.jar > file-0.1.0-\{integration\}.jar.md5
md5sum file-0.1.0-\{integration\}.jar.amd > file-0.1.0-\{integration\}.jar.amd.md5
md5sum file-0.1.0-\{integration\}-src.jar > file-0.1.0-\{integration\}-src.jar.md5
