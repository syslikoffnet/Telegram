#!/usr/bin/env python3
"""Проверяет трассировку анимации отправки — «Логирование» в настройках Pengram.

Мигающий текст не воспроизводится на стенде и не лечится чтением исходников,
поэтому в сборку встроен сборщик данных с устройства. Ошибка здесь означает, что
логирование либо ничего не пишет, либо пишет за счёт производительности, либо
кнопка в настройках не работает, — то есть диагностика снова превращается в
угадывание.

Запуск:
    python3 scripts/test-pengram-send-trace.py
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'TMessagesProj/src/main/java/org/telegram'
RES = ROOT / 'TMessagesProj/src/main/res'

TRACE = JAVA / 'messenger/PengramSendTrace.java'
TRANSITION = JAVA / 'ui/TextMessageEnterTransition.java'
CELL = JAVA / 'ui/Cells/ChatMessageCell.java'
MESSAGE_OBJECT = JAVA / 'messenger/MessageObject.java'
ANIMATOR = JAVA / 'ui/recyclerview/ChatListItemAnimator.java'
SETTINGS = JAVA / 'ui/PengramSettingsActivity.java'
PROVIDER_PATHS = RES / 'xml/provider_paths.xml'

INSTRUMENTED = {
    'перелёт': TRANSITION,
    'строка сообщения': CELL,
    'вёрстка': MESSAGE_OBJECT,
    'аниматор списка': ANIMATOR,
}


def load(path):
    return path.read_text(encoding='utf-8')


def test_collector():
    """Сборщик: по умолчанию выключен, бесплатен, буфер ограничен, не падает."""
    src = load(TRACE)

    assert 'public final class PengramSendTrace' in src
    assert 'KEY = "sendTraceEnabled"' in src, \
        'флаг хранится у PengramConfig вместе с остальными настройками'
    assert 'getIntCached(KEY, 0) != 0' in src, 'логирование должно быть выключено по умолчанию'
    assert 'PengramConfig.setIntValue(KEY, value ? 1 : 0)' in src, \
        'putBoolean у PengramConfig закрытый, булевы флаги живут как int'
    assert 'public static void setEnabled(boolean value)' in src
    # drawMessageText — горячий путь: флаг читается из кэша, а не из настроек
    assert 'private static volatile Boolean cachedEnabled;' in src, \
        'на каждый кадр строки не должно приходиться чтение PengramConfig'
    assert 'final Boolean cached = cachedEnabled;' in src
    assert src.index('cachedEnabled = value;') < src.index('public static synchronized void send()'), \
        'тумблер обязан обновлять кэш сразу'
    assert 'cachedEnabled = null;' in src, \
        'кэш перечитывается на новое сообщение, иначе сброс настроек не подхватится'
    assert 'if (value) {' in src and 'reset();' in src, 'включение начинает новый сеанс сбора'

    assert 'MAX_LINES = 8000' in src, 'кольцевой буфер обязан быть ограниченным'
    assert 'lines.size() > MAX_LINES' in src

    log = src[src.index('public static synchronized void log(String tag'):]
    log = log[:log.index('private static String header()')]
    assert 'if (!enabled())' in log, 'log() обязан выходить сразу, когда сбор выключен'

    write = src[src.index('public static File writeToFile(Context context)'):]
    write = write[:write.index('public static boolean share(Context context)')]
    assert 'catch (Throwable t)' in write and 'FileLog.e(t)' in write, \
        'ошибка записи файла не должна ронять отправку сообщения'
    assert '} finally {' in write and 'writer.close()' in write, 'поток закрывается в любом случае'
    assert 'StandardCharsets.UTF_8' in write, 'лог пишется в utf-8, чтобы кириллица доехала'
    assert 'if (context == null)' in write and 'return null;' in write, 'без контекста — null, а не падение'

    assert 'SystemClock.uptimeMillis()' in src, 'время — монотонное, иначе кадры поедут'
    assert 'Locale.US' in src, 'формат чисел не должен зависеть от локали телефона'
    assert '%8dms %-6s' in src, 'строки трассы должны выравниваться для чтения'
    assert '/ 1000f' not in src, (
        'uptimeMillis уже в миллисекундах: первый лог с устройства показывал '
        '«5.1ms» вместо 5100 мс, и длительность перелёта по нему не измерить')


def test_file_lands_where_provider_can_share_it():
    """Файл пишется в files/logs/ — именно этот каталог отдаёт FileProvider."""
    src = load(TRACE)
    assert 'new File(context.getFilesDir(), "logs")' in src
    assert 'pengram-send-trace-' in src, 'имя файла содержит отметку времени'

    tree = ET.parse(PROVIDER_PATHS)
    covered = [el.get('path') for el in tree.getroot()
               if el.tag == 'files-path' and (el.get('path') or '').strip('/') == 'logs']
    assert covered, 'files/logs/ не описан в provider_paths.xml — файл нельзя отдать наружу'

    assert 'ApplicationLoader.getApplicationId() + ".provider"' in src, \
        'authority провайдера берётся из applicationId, а не зашит строкой'
    assert 'Intent.FLAG_GRANT_READ_URI_PERMISSION' in src
    assert 'Intent.createChooser' in src
    assert 'ACTION_SEND' in src
    assert 'EXTRA_STREAM' in src
    assert 'AndroidUtilities.addToClipboard(dump())' in src, \
        'если поделиться не вышло, лог всё равно надо достать — через буфер обмена'
    assert 'FileLog.e(t)' in src, 'отказ шаринга должен попасть в общий лог приложения'
    assert 'lastFilePath' in src, 'путь к файлу показывается пользователю'


def test_header_records_the_suspects():
    """Шапка лога фиксирует всё, от чего зависит мигание, — иначе лог не объяснить."""
    src = load(TRACE)
    header = src[src.index('private static String header()'):]
    header = header[:header.index('public static synchronized String dump()')]
    for token, why in (
        ('PengramVersion.report()', 'сборка, на которой снят лог'),
        ('getDevicePerformanceClass', 'класс производительности — от него зависит, создаётся ли перелёт'),
        ('getSendTextStyle()', 'стиль текста при отправке — он даёт spans и включает crossfade'),
        ('getInputAnimation()', 'анимация ввода'),
        ('PengramGlassSkin.signature(', 'эффект жидкого стекла на поле ввода'),
        ('SURFACE_INPUT', 'сигнатура берётся именно для поверхности ввода'),
        ('PengramGlassSkin.refractionAllowed()', 'рефракция стекла'),
        ('density', 'плотность экрана — dp(44) в геометрии'),
        ('displaySize', 'размер экрана — от него считается низ списка'),
    ):
        assert token in header, f'в шапке нет «{why}» ({token})'


def test_every_log_call_is_guarded():
    """Ни одна запись не выполняется, когда сбор выключен: сборка не должна тормозить."""
    for name, path in INSTRUMENTED.items():
        lines = load(path).splitlines()
        unguarded = []
        for i, line in enumerate(lines):
            if '.log("' not in line and 'PengramSendTrace.log(' not in line:
                continue
            window = '\n'.join(lines[max(0, i - 14):i + 1])
            if 'PengramSendTrace.enabled()' not in window:
                unguarded.append(i + 1)
        assert not unguarded, f'{name}: запись без проверки enabled() на строках {unguarded}'


def test_transition_records_every_frame_and_every_refusal():
    """Откат: transition — официальный код мастера, трассировки в нём нет.

    Сессионные записи (CTOR/START/FRAME/DRAW/FINISH) ушли вместе со схемой
    по решению пользователя после run401. Трассировка осталась в ячейке,
    ChatListItemAnimator и MessageObject — их проверяют соседние тесты.
    """
    src = load(TRANSITION)
    assert 'PengramSendTrace' not in src, (
        'transition откатан на официальный код мастера — трассировки в нём быть не должно')
    assert 'boolean drawBitmaps = false;' in src
    assert 'if (editText.length() != text.length() || containsSpans)' in src


def test_cell_records_who_paints_the_text():
    """Строка: видно, кто именно и с какой прозрачностью рисует текст в каждый кадр."""
    src = load(CELL)
    assert '"who=" + (drawOnlyText ? "overlay" : "cell")' in src, \
        'запись должна говорить, overlay рисует или строка'
    assert '!caption && !explanation' in src, 'подписи и объяснения не относятся к отправке'
    for token, why in (
        ('origin=', 'origin решает, какую вёрстку рисовать'),
        ('alpha=', 'прозрачность текста — главное при мигании'),
        ('blocks=', 'сколько блоков вёрстки в этот момент'),
        ('plain=', 'сработал ли запасной путь без стилей'),
        ('suppressed=', 'подавлено ли рисование строки перелётом'),
        ('viewAlpha=', 'прозрачность самой строки'),
        ('timeAlpha=', 'отдельная прозрачность времени и галочек'),
        ('ignoreAlpha=', 'игнорируется ли альфа из transitionParams'),
        ('animText=', 'включён ли внутристрочный crossfade текста'),
        ('animProg=', 'прогресс этого crossfade — второй кандидат на мигание'),
        ('outBlocks=', 'есть ли старая копия текста у внутристрочного crossfade'),
        ('visible=', 'видима ли строка'),
        ('transY=', 'смещение строки'),
        ('textXY=', 'координаты текста в строке'),
        ('log("SKIP"', 'текст вообще не нарисован и почему'),
        ('reason=no_blocks', 'нет вёрстки'),
        ('reason=alpha_zero', 'нулевая прозрачность'),
        ('log("CELL", "setSuppressed="', 'смена подавления строки'),
        ('log("CELL", "finishEnterTransition gen="', 'кто и с каким поколением снял подавление'),
                ('matched=', 'совпало ли поколение — иначе подавление снимет чужой перелёт'),
        ('cell=" + System.identityHashCode(this)', 'какая именно ячейка меняет состояние'),
        ('public boolean isEnterTransitionInProgress()', 'перелёту нужно знать состояние строки'),
    ):
        assert token in src, f'строка не пишет «{why}» ({token})'


def test_layout_records_what_the_row_will_draw():
    """Вёрстка: что получит строка, включая запасной путь и стили."""
    src = load(MESSAGE_OBJECT)
    assert 'log("LAYOUT"' in src
    for token, why in (
        ('softWrap=', 'мягкая вёрстка меняет отображаемый текст'),
        ('zwsp=', 'сколько нулевых пробелов она вставила'),
        ('blocks=', 'число блоков вёрстки'),
        ('hasLayout=', 'есть ли текст вообще'),
        ('layoutCreated=', 'создан ли layout в этой сборке'),
        ('failures=', 'число неудачных попыток — по нему видно цикл пересборок'),
        ('fallback=', 'сработал ли запасной путь без стилей'),
        ('spans=', 'число стилевых интервалов'),
    ):
        assert token in src, f'вёрстка не пишет «{why}» ({token})'


def test_animator_records_whether_the_transition_happens():
    """Аниматор: создан ли перелёт и почему нет — без этого непонятно, что смотреть."""
    src = load(ANIMATOR)
    assert src.count('log("ANIM", "created="') == 1, \
        'запись одна на эпизод: и когда перелёт создан, и когда нет'
    assert src.count('PengramSendTrace.send()') == 1, 'граница эпизода пишется до решения о перелёте'
    for token, why in (
        ('out=', 'своё сообщение или входящее — иначе лог не отфильтровать'),
        ('sending=', 'сообщение ещё в статусе отправки'),
        ('textLen=', 'длина текста строки в момент появления'),
        ('cellH=', 'высота строки'),
        ('perfOk=', 'класс производительности'),
        ('nearEnough=', 'строка далеко за пределами экрана — перелёт не создаётся'),
        ('transY=', 'смещение строки'),
        ('listH=', 'высота списка'),
        ('cellAlpha=', 'прозрачность строки в момент создания'),
        ('PengramSendTrace.send()', 'начало отправки — новый лог и шапка'),
    ):
        assert token in src, f'аниматор не пишет «{why}» ({token})'


def test_settings_rows():
    """Кнопки в настройках: тумблер через tgCheck, отдача лога и очистка."""
    src = load(SETTINGS)
    for token in ('BTN_SEND_TRACE_TOGGLE = 1070', 'BTN_SEND_TRACE_SHARE = 1071',
                  'BTN_SEND_TRACE_CLEAR = 1072'):
        assert token in src, f'нет id {token}'

    assert 'UItem.asHeader(getString(R.string.PengramSendTraceHeader))' in src
    # именно tgCheck: он заводит getter/toggle в реестр, иначе строка не перепривязывается
    assert 'tgCheck(BTN_SEND_TRACE_TOGGLE, getString(R.string.PengramSendTrace),' in src
    assert 'PengramSendTrace::enabled' in src
    assert 'PengramSendTrace.setEnabled(' in src
    toggle = src[src.index('tgCheck(BTN_SEND_TRACE_TOGGLE'):]
    toggle = toggle[:toggle.index('UItem.asSettingsCell(BTN_SEND_TRACE_SHARE')]
    assert 'updateList();' in toggle, \
        'после тумблера надо пересобрать строки: рядом показывается число собранных строк'
    assert 'UItem.asSettingsCell(BTN_SEND_TRACE_SHARE' in src
    assert 'UItem.asButton(BTN_SEND_TRACE_CLEAR' in src
    assert 'UItem.asShadow(getString(R.string.PengramSendTraceInfo))' in src

    assert 'case BTN_SEND_TRACE_SHARE:' in src
    assert 'PengramSendTrace.share(' in src
    assert 'case BTN_SEND_TRACE_CLEAR:' in src
    assert 'PengramSendTrace.reset()' in src
    assert 'PengramSendTrace.dump()' in src, 'если поделиться не вышло, лог копируется в буфер'
    assert 'private CharSequence sendTraceSummary()' in src
    assert 'PengramSendTrace.lineCount()' in src

    trace_ids = [int(m) for m in re.findall(r'BTN_SEND_TRACE_[A-Z]+ = (\d+);', src)]
    assert len(trace_ids) == 3 and len(set(trace_ids)) == 3, 'id кнопок трассировки уникальны'
    others = [int(m) for m in re.findall(r'private static final int (?:BTN_[A-Z0-9_]+) = (\d+);', src)
              if int(m) != 1447]
    assert len(others) == len(set(others)), 'новые id не должны пересекаться с существующими'


def test_referenced_resources_exist():
    """Локального javac нет, поэтому ресурсы проверяем сами.

    Ровно на этом в первый раз и споткнулась сборка: в настройках появился
    R.raw.infotoast, которого в проекте никогда не было.
    """
    src = load(SETTINGS)
    blocks = []
    for marker in ('case BTN_SEND_TRACE_SHARE', 'case BTN_SEND_TRACE_CLEAR'):
        start = src.index(marker)
        blocks.append(src[start:start + 900])
    fill = src[src.index('Диагностика анимации отправки'):]
    blocks.append(fill[:fill.index('private CharSequence sendTraceSummary()')])

    raw = RES / 'raw'
    for block in blocks:
        for name in set(re.findall(r'R\.raw\.([A-Za-z0-9_]+)', block)):
            assert list(raw.glob(name + '.*')), f'нет ресурса R.raw.{name}'
        for name in set(re.findall(r'R\.drawable\.([A-Za-z0-9_]+)', block)):
            found = any(list(d.glob(name + '.*')) for d in RES.glob('drawable*'))
            assert found, f'нет ресурса R.drawable.{name}'
        for name in set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', block)):
            for locale in ('values', 'values-ru'):
                xml = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
                assert f'name="{name}"' in xml, f'{locale}: нет R.string.{name}'


def test_strings_in_both_locales():
    names = ('PengramSendTraceHeader', 'PengramSendTrace', 'PengramSendTraceOff',
             'PengramSendTraceEmpty', 'PengramSendTraceLines', 'PengramSendTraceShare',
             'PengramSendTraceClear', 'PengramSendTraceCleared', 'PengramSendTraceNothing',
             'PengramSendTraceCopied', 'PengramSendTraceSavedTo', 'PengramSendTraceInfo')
    for locale in ('values-ru', 'values'):
        tree = ET.parse(RES / locale / 'strings.xml')
        present = {el.get('name') for el in tree.getroot() if el.tag == 'string'}
        for name in names:
            assert name in present, f'{locale}: нет строки {name}'


def _java_method(source, name):
    """Тело метода по балансу скобок — так же его достают харнессы."""
    match = re.search(r'(?:(?:public|private|protected)\s+)?(?:static\s+)?[\w<>]+\s+'
                      + re.escape(name) + r'\([^{};]*\)\s*\{', source)
    if not match:
        return ''
    depth, end = 1, match.end()
    while depth and end < len(source):
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[match.start():end]


def test_jvm_harnesses_stub_the_trace():
    """Харнессы компилируют продакшн-код дословно — им нужна заглушка трассировки.

    Ровно так сборка и упала в первый раз: ViewportHarness встраивает блок
    ``if (alpha == 0)`` из ячейки, VisibilityHarness — сеттер подавления и
    ``finishEnterTransition``; оба куска теперь ссылаются на PengramSendTrace,
    которого в отдельной компиляции нет. Локально это не видно: без JDK обе
    JVM-части пропускаются, ошибку показывает только CI.
    """
    java = ROOT / 'TMessagesProj/src/main/java/org/telegram'
    checked = []
    for script in sorted((ROOT / 'scripts').glob('test-pengram-*.py')):
        if script.name == Path(__file__).name:
            continue
        text = script.read_text(encoding='utf-8')
        if 'javac' not in text:
            continue
        # переменная -> файл, из которого сьют читает исходник
        sources = {}
        for var, rel in re.findall(r"(\w+)\s*=\s*\((?:JAVA|java|root[^)]*)\s*/\s*'([^']+)'\)\.read_text\(\)", text):
            path = java / rel
            if path.exists():
                sources[var] = path.read_text(encoding='utf-8')
        needs_stub = False
        for var, name in re.findall(r"method\(\s*(\w+)\s*,\s*'([\w]+)'\s*\)", text):
            if var in sources and 'PengramSendTrace' in _java_method(sources[var], name):
                needs_stub = True
        # срез блока alpha=0 из drawMessageText — второй способ встроить трассировку
        if re.search(r"\.(?:index|rfind)\(\s*'[^']*if \(alpha == 0\)", text):
            needs_stub = True
        if needs_stub:
            checked.append(script.name)
            assert 'PengramSendTrace' in text, (
                f'{script.name}: встраивает инструментированный продакшн-код, но не даёт '
                'харнессу заглушку PengramSendTrace — CI упадёт на javac')
    assert len(checked) >= 2, f'проверка нашла только {checked} — она больше ничего не страхует'


def test_harness_embedded_methods_stay_compilable():
    """Записи внутри встраиваемых методов не должны ссылаться на поля ячейки.

    JVM-модель в scripts/test-pengram-text-visibility.py повторяет
    ``setEnterTransitionInProgress`` и ``finishEnterTransition`` дословно, но
    объявляет только свои поля. Стоит добавить в запись ``currentMessageObject``
    — и CI падает на javac, а локально это не видно: без JDK модель
    пропускается. Так и вышло в первый раз.
    """
    cell_src = load(CELL)
    available = {'b', 'generation', 'pengramEnterGeneration', 'enterTransitionInProgress'}
    keywords = {'org', 'telegram', 'messenger', 'PengramSendTrace', 'log', 'null',
                'true', 'false', 'this'}
    for name in ('setEnterTransitionInProgress', 'finishEnterTransition'):
        body = _java_method(cell_src, name)
        assert body, f'метод {name} не найден'
        for call in re.findall(r'PengramSendTrace\.log\((.*?)\);', body, re.S):
            without_literals = re.sub(r'"[^"]*"', '""', call)
            # составные имена вида a.b.c — берём только последнее звено
            without_qualified = re.sub(r'\w+(?:\.\w+)+', 'QUALIFIED', without_literals)
            used = set(re.findall(r'\b([A-Za-z_]\w*)\b', without_qualified)) - keywords - {'QUALIFIED'}
            unknown = used - available
            assert not unknown, (
                f'{name}: запись трассировки использует {sorted(unknown)} — '
                'в JVM-модели этих символов нет, javac в CI упадёт')


def test_tracing_does_not_change_the_send_path():
    """Сбор данных не должен менять поведение: обе правки отправки остаются как есть."""
    src = load(TRANSITION)
    assert 'boolean drawBitmaps = false;' in src, 'официальное поведение отрисовки'
    assert 'if (editText.length() != text.length() || containsSpans)' in src, \
        'условие официальной ветки crossfade не тронуто'
    start = src[src.index('public void start()'):]
    start = start[:start.index('private boolean isRtlLine')]
    assert 'setEnterTransitionInProgress' not in start, \
        'как в официальном Telegram: флаг ставит конструктор, а не start()'
    ctor = src[src.index('public TextMessageEnterTransition(ChatMessageCell'):]
    ctor = ctor[:ctor.index('public void start()')]
    flag = ctor.index('messageView.setEnterTransitionInProgress(true);')
    assert 'return;' not in ctor[flag:ctor.rindex('}')], \
        'после установки флага в конструкторе нет выходов — иначе ячейка останется глухой'
    assert 'bail=empty_edit_text' not in src, \
        'bail пустого поля — сессионная схема, откатан вместе с transition'
    assert 'CharSequence text = messageView.getMessageObject().messageText;' in src, \
        'официальный источник текста перелёта (messageText) обязатен'
    assert 'PengramSendTrace' not in src, \
        'transition откатан на официальный код мастера — трассировки в нём нет'
    # записи не должны попадать в условия отрисовки
    assert re.search(r'if \([^)]*PengramSendTrace[^)]*\)\s*\{\s*(?!org\.telegram|@@|\})', src) is None, \
        'трассировка не должна управлять потоком отрисовки'


def main():
    tests = [
        test_collector,
        test_file_lands_where_provider_can_share_it,
        test_header_records_the_suspects,
        test_every_log_call_is_guarded,
        test_transition_records_every_frame_and_every_refusal,
        test_cell_records_who_paints_the_text,
        test_layout_records_what_the_row_will_draw,
        test_animator_records_whether_the_transition_happens,
        test_settings_rows,
        test_referenced_resources_exist,
        test_strings_in_both_locales,
        test_jvm_harnesses_stub_the_trace,
        test_harness_embedded_methods_stay_compilable,
        test_tracing_does_not_change_the_send_path,
    ]
    failed = 0
    for test in tests:
        try:
            test()
        except AssertionError as e:
            failed += 1
            print(f'FAIL {test.__name__}: {e}')
    if failed:
        print(f'{failed} проверок провалено')
        return 1
    print(f'OK: трассировка отправки — {len(tests)} проверок')
    return 0


if __name__ == '__main__':
    sys.exit(main())
