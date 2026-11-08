# Android

## Сборка

```bash
export JAVA_HOME=/path/to/jdk-17
export MEMENTO_AI_BASE_URL=https://memento.example.com/api/mobile/v1
export MEMENTO_APP_BOOTSTRAP_SECRET=replace-with-gateway-secret
../../scripts/prepare-offline-models.sh
gradle :app:assembleDebug
```

Требуются Android SDK 35, JDK 17, Gradle 8.11+ и около 700 МБ свободного места. Скрипт проверяет SHA-256 и добавляет GigaAM RNNT INT8 и Nemotron в assets APK. Большие веса не хранятся в Git; готовый APK уже содержит их и работает без сетевой загрузки моделей.

