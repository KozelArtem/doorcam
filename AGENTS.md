# Правила работы с репозиторием

- После проверки завершённых изменений сделай коммит и отправь его в удалённую ветку, которую отслеживает текущая ветка. Если пользователь просит оставить изменения локально, следуй его указанию.
- Включай в коммит только файлы и правки, относящиеся к задаче. Подтверди успешный push или сообщи, что ему помешало.
- При работе с Android-интерфейсом пользуйся [паттерном интерфейса Doorcam](docs/android-ui.md).

## Сборка и установка Android на Meizu из WSL

Из корня репозитория собери APK инкрементально. Gradle использует локальный кэш и сохраняет работающий демон для следующих сборок.

```sh
export JAVA_HOME="$HOME/tools/jdk-17.0.21+9"
export ANDROID_HOME="$HOME/tools/android-sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
(cd android && ./gradlew --offline :app:assembleDebug)
```

- APK лежит в `android/app/build/outputs/apk/debug/app-debug.apk`. Для проверки добавь к вызову Gradle `:app:testDebugUnitTest`. Если не хватает зависимостей в кэше, повтори сборку с доступом к сети, убрав `--offline`.
- Для обновления поверх установленного Doorcam используй существующий `~/.android/debug.keystore`. Если подпись не совпала, найди прежний ключ. Удаление приложения стирает настройки и локальный архив.

Meizu Note 9 работает на Android 9. Пока TCP-отладка включена, достаточно подключиться через Linux ADB из SDK. Последний адрес телефона указан в примере. Если адрес изменился, возьми текущий из настроек Wi‑Fi телефона.

```sh
phone=192.168.0.103:5555
adb connect "$phone"
adb -s "$phone" get-state
adb -s "$phone" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$phone" shell pm path by.doorcam.app
```

Ожидаемые ответы при проверке и установке — `device`, `Success` и путь к установленному APK. Если TCP после перезагрузки выключен, подключи телефон по USB к Windows. Узнай серийный номер через `adb.exe devices -l`, подставь его вместо `SERIAL` в `adb.exe -s SERIAL tcpip 5555` и подключись из WSL. При статусе `unauthorized` разблокируй телефон и подтверди запрос на отладку.
