"""
Copyright 2024 Datastrato Pvt Ltd.
This software is licensed under the Apache License version 2.
"""

from fsspec import AbstractFileSystem
from fsspec.implementations.arrow import ArrowFSWrapper
from fsspec.implementations.local import LocalFileSystem
from pyarrow.fs import HadoopFileSystem

from gravitino.exceptions.gravitino_runtime_exception import GravitinoRuntimeException
from gravitino.filesystem.filesystem_context import FileSystemContext
from gravitino.filesystem.hadoop_environment import HadoopEnvironment


class SimpleFileSystemContext(FileSystemContext):
    _filesystem: AbstractFileSystem
    _hadoop_env: HadoopEnvironment

    def __init__(self, uri: str):
        if uri.startswith("hdfs://") or uri.startswith("lavafs://"):
            self._hadoop_env = HadoopEnvironment()
            self._filesystem = ArrowFSWrapper(HadoopFileSystem.from_uri(uri))
        elif uri.startswith("file:/"):
            self._filesystem = LocalFileSystem()
        else:
            raise GravitinoRuntimeException(
                f"Storage type doesn't support now. Path:{uri}"
            )

    def get_filesystem(self) -> AbstractFileSystem:
        return self._filesystem

    def get_secret(self) -> str:
        raise NotImplementedError()
