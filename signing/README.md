# Release signing

`itvs-release.jks` is gitignored. To rebuild release APKs locally:

```bash
keytool -genkeypair -v \
  -keystore signing/itvs-release.jks \
  -alias itvs \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass itvs-connect-release \
  -keypass itvs-connect-release \
  -dname "CN=iTVS Connect, OU=Open Source, O=iTVS, L=Chennai, ST=TN, C=IN"
```

Override passwords with Gradle properties `ITVS_STORE_PASSWORD`, `ITVS_KEY_ALIAS`, `ITVS_KEY_PASSWORD` if desired.
