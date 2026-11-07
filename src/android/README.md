# Android

## Сборка

```bash
export JAVA_HOME=/path/to/jdk-17
export MEMENTO_AI_BASE_URL=https://memento.example.com/api/mobile/v1
export MEMENTO_APP_BOOTSTRAP_SECRET=replace-with-gateway-secret
gradle :app:assembleDebug
```

Требуются Android SDK 35, JDK 17 и Gradle 8.11+. Модели GigaAM и Nemotron загружаются приложением при первом запуске и не хранятся в Git.

