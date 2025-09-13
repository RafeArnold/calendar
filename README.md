## Build

Due to [a node-gradle plugin issue](https://github.com/node-gradle/gradle-node-plugin/issues/152), the `npmInstall`
gradle task (and subsequently `processResources`) doesn't run reliably from the IntelliJ UI out-of-the-box on macOS.
As a fix, the node plugin is configured to point to explicit `npm` and `npx` binaries.
Specifically the binary located in the directory defined by `NVM_BIN`.
This therefore requires [nvm](https://github.com/nvm-sh/nvm) to be installed.
