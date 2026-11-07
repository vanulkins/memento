# AI gateway

Gateway принимает только подписанные запросы Memento, ограничивает частоту, блокирует повтор nonce и перенаправляет разрешённые задачи в OpenAI-compatible API.

## Развёртывание

```bash
sudo mkdir -p /opt/memento-ai
sudo cp server.mjs package.json /opt/memento-ai/
sudo cp memento-ai.service /etc/systemd/system/
sudo cp nginx.conf /etc/nginx/sites-available/memento-ai
sudo cp ../../config/example.env /etc/memento-ai.env
sudo chmod 600 /etc/memento-ai.env
sudo systemctl daemon-reload
sudo systemctl enable --now memento-ai
sudo nginx -t && sudo systemctl reload nginx
```

Перед запуском замените домен, пути сертификата и все значения `replace-with-*`. `APP_BOOTSTRAP_SECRET` должен совпадать с `MEMENTO_APP_BOOTSTRAP_SECRET`, использованным при сборке APK.

