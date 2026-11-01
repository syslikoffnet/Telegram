# Разбор обфусцированных payload'ов в .plugin

`scripts/inspect_plugin_payload.py` — статический декодер «хвостов» вида

```python
_CACHE_KEY = b';\x84ea!>\xdb\xf1'
_CACHE_BLOB = "..."
...
exec(compile(_restore_cache(_CACHE_KEY, _CACHE_BLOB).decode("utf-8"),
             "<cache>", "exec"), globals())
```

Такой блок встречается в сторонних плагинах для exteraGram/Turmx: видимая часть
плагина безопасна, а весь реальный код лежит в закодированной строке и выполняется
через `exec`. **Читать такое в `exec` нельзя** — именно так крадут сессии, токены
и ключи шифрования.

Скрипт повторяет распаковку (`b85 → zlib → XOR`), но вместо исполнения пишет
результат в файл и отмечает опасные места: сеть, чтение `tdata`/`key_datas`,
`api_hash`, запуск процессов, `eval/exec`, IP-адреса и URL.

## Запуск

```bash
# исходник плагина целиком
python3 scripts/inspect_plugin_payload.py suspicious.plugin --extract-strings

# только строка BLOB (можно вставлять как есть, с переносами и &lt; &gt;)
python3 scripts/inspect_plugin_payload.py blob.txt --out decoded.py

# если ключ другой или известен вручную (hex-байты)
python3 scripts/inspect_plugin_payload.py blob.txt --key 3b 84 65 61 21 3e db f1
```

Ключ из самого плагина подхватывается автоматически (`_CACHE_KEY`), HTML-экранирование
(`&amp;`, `&lt;`, `&gt;`), переносы строк и кавычки нормализуются.

## Что вы получите

* `*.decoded.py` — восстановленный код с шапкой «НЕ ИСПОЛНЯТЬ»;
* отчёт: импорты, индикаторы подозрительной активности со ссылками на строки,
  список строковых литералов (`--extract-strings`).

Код-статус `0` — payload распакован; `1` — обфусцированного блока нет либо ключ
не подошёл (попробуйте `--key`).

## Второй тип: «защищённый» блок `_x7a2` / `_x7a3`

Некоторые плагины (пример: `Comfort Lyrics++`) прячут код иначе:

```python
def _x7a3(self):
    _ik  = sha256(bytes([185,9,58,37,79,236,68,252]) + __id__.encode()).digest()
    _tab = (71,217,83,...); _idx = (4,73,52,...); _ms = 151
    _k   = bytes(((_tab[_idx[t]] - _ms) & 255) ^ _ik[t] for t in range(32))
    _ct  = b64decode(_enc.translate(maketrans(_abc, _std)) + "==")   # свой алфавит b64
    if sha256(_k + _n + _ct).digest() != _mac: return                # контроль целостности
    _src = zlib.decompress(keystream_xor(_ct, _k, _n))
    exec(compile(_src, "<n>", "exec"), globals())
```

Ключ выводится из `__id__` плагина, поэтому payload привязан к конкретному файлу.
`exec(..., globals())` означает, что скрытый код может **переопределить любую
функцию модуля** — включая `requests`, `get_current_track_info`, хуки и сам `_x7a3`.

```bash
python3 scripts/unpack_nw_loader.py suspicious.plugin --extract-strings
```

Скрипт разбирает константы из исходника, проверяет MAC и пишет `*.decoded.py`
с шапкой «НЕ ИСПОЛНЯТЬ». Если MAC не сходится — файл повреждён или это другая
версия схемы (тогда ключ/соль нужно смотреть глазами).

## Проверка

```bash
python3 scripts/test-inspect-plugin-payload.py
python3 scripts/test-unpack-nw-loader.py
python3 scripts/test-scan-nw-loader-iocs.py
```

Тесты собирают синтетические плагины обеих схем (b85/zlib/XOR и keystream/zlib с MAC),
проверяют распаковку, поиск индикаторов и корректный отказ при подмене `__id__`
или на обычном, необфусцированном плагине.

## Важно

* Ничего не запускайте из восстановленного файла: `python3 decoded.py` = выполнить
  чужой код на своём устройстве.
* Скрипт не претендует на «поиск по IP» — в Telegram-клиенте, а тем более в плагине,
  нет и не может быть API, который отдаёт IP, устройства или ключи другого человека.
  Плагины вида «снос аккаунта», «покажу IP собеседника», «выгрузка чужого аккаунта»
  продают выдуманный результат — а в обфусцированном блоке обычно уезжает как раз
  ваш `tdata` на чужой сервер.

## Реальный случай

`Comfort Lyrics++` после расшифровки оказался RCE-агентом с C2 и 100 «компаньонами»
для самовосстановления — полный разбор, IOC и порядок удаления:
[`docs/comfort-lyrics-malware.md`](comfort-lyrics-malware.md).
Быстрая проверка папки с плагинами:

```bash
python3 scripts/scan_nw_loader_iocs.py /path/to/plugins
```
