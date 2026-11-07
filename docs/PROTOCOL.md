# Протокол Android ↔ AI gateway

## Регистрация установки

`POST /api/mobile/v1/register`

```json
{
  "install_id": "uuid",
  "proof": "hmac_sha256(APP_BOOTSTRAP_SECRET, install_id)"
}
```

Ответ содержит случайный `install_secret` со сроком жизни 30 дней.

## AI-запрос

`POST /api/mobile/v1/ai`

Заголовки:

- `X-Memento-Install` — ID установки;
- `X-Memento-Timestamp` — Unix time в миллисекундах;
- `X-Memento-Nonce` — уникальный UUID запроса;
- `X-Memento-Signature` — HMAC-SHA256 от `timestamp + "\n" + nonce + "\n" + sha256(body)`.

Тело:

```json
{
  "task": "summary",
  "messages": [
    {"role": "system", "content": "Инструкция"},
    {"role": "user", "content": "Стенограмма"}
  ]
}
```

Разрешённые задачи: `summary`, `metadata`, `meeting_chat`, `knowledge_chat`. Gateway отправляет последние 12 сообщений в OpenAI-compatible endpoint и возвращает только текст ответа, название модели и тип задачи.

## Восстановление и ошибки

- подпись или просроченный timestamp: `401`;
- неверное доказательство регистрации: `403`;
- превышение rate limit: `429`;
- ошибка AI-провайдера: `502`;
- клиент при `401` удаляет локальный install secret, повторно регистрируется и повторяет запрос один раз;
- локальная транскрипция и диаризация продолжают работать без gateway.

