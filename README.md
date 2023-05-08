# cljonda

Integration with [conda](https://github.com/conda/conda) to make it easier to provide native dependencies for jvm libraries.

## Rationale

There are many good c libraries that don't good jvm counterparts. Support for wrapping c libraries is very good. The annoying part is that the tools for obtaining jvm libraries (ie. maven) don't integrate with the tools for obtaining native libraries (ie. package managers).

Common practice for jvm libraries that wrap native libraries is to either offload the problem of obtaining native libraries onto users or do the bare minimum to build the necessary native dependencies. For relatively isolated native libraries, building a bespoke pipeline for providing native deps is manageable, albeit cumbersome. For native dependencies that have other native dependencies, it becomes unwieldy very quickly.

Fortunately, building native libraries and their dependencies is already a solved problem (well, not really. shared libraries are still a nightmare).

The idea behind cljonda is to allow conda to do all the hard work of building native dependencies and providing simple tools for making those binaries available as maven deps.

## License

Copyright 2021 Adrian Smith. Cljonda is licensed under Apache License v2.0.

