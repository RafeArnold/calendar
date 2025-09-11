## Build

Due to [a node-gradle plugin issue](https://github.com/node-gradle/gradle-node-plugin/issues/152), the `npmInstall`
gradle task (and subsequently `processResources`) doesn't run reliably from the IntelliJ UI.
Instead, run it from the terminal `./gradlew processResources`.
You may need to run `./gradlew --stop` first.
