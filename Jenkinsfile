@Library('bwmp') _

gradlePlugin(
    toolchains: [
        JAVA_HOME_8_X64: 'Java 8',
        JAVA_HOME_17_X64: 'Java 17',
        JAVA_HOME_21_X64: 'Java 21',
        JAVA_HOME_25_X64: 'Java 25'
    ],
    buildCommand: './gradlew build --no-daemon',
    artifacts: 'build/artifacts/*.jar'
)
