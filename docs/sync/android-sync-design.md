# TypendruckerGram Sync для Android — дизайн

Дата: 2026-11-01. Протокол общий с Desktop: [protocol.md](protocol.md),
векторы: [test-vectors.json](test-vectors.json). Этот документ описывает только
то, как протокол встраивается в форк exteraless.

## Цель

Удалённые и изменённые сообщения, сохранённые на телефоне, появляются на ПК и
наоборот. Каждое устройство хранит всё у себя вечно; сервер
(`https://unionofjackbox.tokenized.name/typsync/`) — только обменный узел с
хранением до 5 дней. Всё шифруется на устройстве ключом владельца.

## Что уже есть в exteraless

Подсистема AyuGram (`com.radolyn.ayugram`) сохраняет историю в Room-базу:

- `EditedMessage` — каждая предыдущая версия сообщения;
- `DeletedMessage` (+ `DeletedMessageReaction`) — удалённые сообщения.

Точки входа — `AyuMessagesController.onMessageEditedInner` и
`onMessageDeletedInner`; их вызывают `MessagesController`, `MessagesStorage`,
`SecretChatHelper`. ID диалогов на Android уже совпадают с протоколом:
пользователь — его ID, группа/канал — минус «голый» ID без `-100`.

Отдельный кеш сообщений, как на Desktop (`CachedMessage`), не нужен:
Android и так держит загруженные сообщения в `cache4.db`, поэтому при удалении
содержимое обычно уже известно.

## Компоненты (пакет `app.typendruckergram.sync`)

| Файл | Ответственность |
|---|---|
| `SyncCrypto` | X25519 + HKDF-SHA256 + AES-256-GCM, код сопряжения. BouncyCastle (`bcprov-jdk18on`) для X25519, JCA для остального. Обязан проходить `test-vectors.json` |
| `SyncEvents` | `Event`/`Snapshot` ↔ канонический JSON; ключи дедупликации; `AyuMessageBase` ↔ `Snapshot` (текст, entities из TL ↔ JSON протокола, reply, grouped, topic) |
| `SyncStore` | Room-таблицы `SyncOutbox`, `SyncSeen`, `SyncState` — отдельная база `typsync.db`, миграции AyuDatabase не трогаем |
| `SyncClient` | OkHttp к relay: owners, pair, events, blobs, heartbeat, status, settings; прокси Telegram не используется |
| `SyncWorker` | цикл: отправить outbox → забрать события → применить; heartbeat. Работает, пока приложение в памяти |
| `SyncApply` | применение входящих событий через `AyuMessagesController` |
| `SyncMedia` | шифрование и загрузка вложений до лимита, скачивание по `blobId` в папку AyuGram |
| `SyncSettingsActivity` | включение, сопряжение (показ кода / ввод кода / QR), лимит медиа, статус |

## Поток исходящих

1. После успешной вставки в `onMessageEditedInner` → `Edit` с `previous` =
   снимок старой версии и `newEditDate`.
2. После вставки в `onMessageDeletedInner` → копим удаления по диалогу
   200 мс и шлём одно `Delete` (`dialog` для каналов/супергрупп, иначе 0),
   со снимками всех известных сообщений.
3. Медиа: если размер ≤ лимита, файл шифруется, `PUT /v1/blobs/{id}`,
   `media.blobId` в снимке. Больше лимита — снимок без файла, только
   `kind/mime/size/name`.
4. Событие пишется в `SyncOutbox` в той же транзакции логики, что и Ayu-запись;
   отправка — пачками до 500, ретраи 5 с → 5 мин.

## Поток входящих

1. `GET /v1/events` пока `more`; каждый блоб расшифровать; ключи
   дедупликации проверить по `SyncSeen` (повтор — пропуск).
2. `Edit`: если ревизии с таким `editDate` для `(userId, dialog, msgId)`
   нет — вставить `EditedMessage` из `previous`.
3. `Delete`: для каждого `msgId`, которого нет в `DeletedMessage`, —
   содержимое берём из локального `cache4.db`, если есть, иначе из снимка
   события; вставка через ту же логику, что `onMessageDeletedInner`, но с
   флагом «пришло с сервера», чтобы не отправлять событие обратно.
4. После вставки — `MESSAGE_EDITED_NOTIFICATION`/обновление открытого чата,
   как делает Ayu сейчас.
5. Медиа скачивается сразу при применении события в папку вложений AyuGram:
   сервер держит блобы только 5 дней, а лимит размера уже отсёк большие файлы.
   Если блоба на сервере нет — сообщение сохраняется без файла.

## Учётная запись Telegram

События содержат `account` (ID пользователя Telegram). Применяем только к
аккаунту с тем же ID; события других аккаунтов храним в `SyncSeen` как
применённые, чтобы не застревать.

## Сопряжение

- Первое устройство (ПК или телефон) создаёт владельца:
  `POST /v1/owners` с инвайтом сервера.
- Второе — по коду `XXXX-XXXX-XXXX` (ввод или QR): `pair/claim`,
  расшифровка `ownerPrivate` ключом из кода.
- Ключ владельца хранится в `EncryptedSharedPreferences`.

## Ошибки и пределы

- Сеть — ретраи с удвоением, без потери outbox.
- `trimmed: true` — показать в настройках «часть данных на сервере удалена
  по квоте».
- Битый или чужой блоб — пропуск и запись в лог.
- Плагины exteraless не получают доступа к ключам и очереди синхронизации.

## Тестирование

- JVM-тест `SyncCryptoTest` на `test-vectors.json` (копия из Desktop).
- JVM-тесты `SyncEvents`: сериализация, дедуп-ключи, разбор entities.
- Ручной E2E: телефон + devtool (`sync-server/typsync/devtool.py`) —
  правка и удаление в обе стороны, повторная доставка, медиа до и сверх лимита.

## Порядок работ

1. `SyncCrypto` + векторный тест в CI.
2. `SyncEvents` + `SyncStore`.
3. `SyncClient` + `SyncWorker` (без UI, включение через скрытую настройку).
4. Хуки в `AyuMessagesController`, `SyncApply`.
5. `SyncMedia`.
6. Экран настроек и сопряжение.
7. E2E с Desktop.
