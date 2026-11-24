# Tantal Calculator

The calculator for Tantal (`app.tantal.calculator`) uses a Compose interface
and a Rust engine for exact arithmetic and equations.

## Building

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

On Linux, install JDK 21, Rust 1.95, and the Android SDK with platform
`android-37.2` and NDK `30.0.16248370`. Set `sdk.dir` in `local.properties`
and install the Android Rust targets:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
```

## Contributing

See the [contribution rules](https://github.com/tantal-os/.github/blob/main/CONTRIBUTING.md)
and the [security policy](https://github.com/tantal-os/.github/blob/main/SECURITY.md).
Report vulnerabilities privately, not in an issue.

## License

Apache-2.0, see `LICENSE`. The bundled Google Sans Flex font is under the
SIL Open Font License, see `app/GoogleSansFlex-OFL.txt`.
