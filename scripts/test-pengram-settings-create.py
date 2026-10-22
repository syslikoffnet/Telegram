#!/usr/bin/env python3
"""Cold settings creation: source contracts + optional JVM model, NOT Android UI testing."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
menu = (java / 'ui/ActionBar/ActionBarMenuItem.java').read_text()
create = settings.split('public View createView(Context context)', 1)[1].split('private void pengramStyleSearch()', 1)[0]
# Do not "fix" the crash by dropping the custom field or making search open on entry.
attach = create.index('searchItem.getSearchContainer().addView(pengramSearchField,')
ensure = create.index('searchItem.getSearchField();')
assert create.index('.setIsSearchField(true)') < ensure < attach
assert create.index('pengramSearchField = new ') < ensure
assert 'openSearch(' not in create and 'toggleSearch(' not in create
assert 'if (searchItem.getSearchContainer() != null)' not in create
assert 'pengramStyleSearch();' in create and 'pengramSearchField.updateColors();' in settings

def method(source, signature):
    start = source.index(signature)
    end = source.index('\n    }', start) + 6
    return source[start:end]

# Use the actual production accessors: getContainer does not initialise lazily,
# whereas getSearchField does. Do not change shared getter behaviour for other screens.
field = method(menu, 'public EditTextBoldCursor getSearchField()')
container = method(menu, 'public FrameLayout getSearchContainer()')
configure = method(menu, 'public ActionBarMenuItem setIsSearchField(boolean value, boolean wrapInScrollView)')
assert 'checkCreateSearchField();' in field
assert 'checkCreateSearchField();' not in configure
assert 'return searchContainer;' in container
attachment = create[ensure:create.index(';', attach) + 1]
assert attachment.count('searchItem.getSearchField();') == 1
assert attachment.count('.addView(') == 1
javac, runtime = shutil.which('javac'), shutil.which('java')
if javac and runtime:
    # Android classes are minimal fakes. This executes the production call order
    # and getters, not the real ActionBar layout or Android lifecycle.
    code = '''public class SettingsCreateCheck {
      static class EditTextBoldCursor { }
      static class FrameLayout {
        int children;
        void addView(Object view, Object params) {
          if (view == null || params == null) throw new AssertionError();
          children++;
        }
      }
      static class Gravity { static final int CENTER_VERTICAL = 1; }
      static class LayoutHelper {
        static final int MATCH_PARENT = -1;
        static Object createFrame(int w,int h,int g,int l,int t,int r,int b) { return new Object(); }
      }
      static class ActionBarMenuItem {
        Object parentMenu = new Object();
        boolean isSearchField, wrapSearchInScrollView;
        FrameLayout searchContainer;
        EditTextBoldCursor searchField;
        int creations;
        void checkCreateSearchField() {
          if (searchContainer == null && isSearchField) {
            searchContainer = new FrameLayout();
            searchField = new EditTextBoldCursor();
            creations++;
          }
        }
    ''' + configure + field + container + '''
      }
      static void attach(ActionBarMenuItem searchItem, Object pengramSearchField) {
    ''' + attachment + '''
      }
      public static void main(String[] args) {
        ActionBarMenuItem broken = new ActionBarMenuItem().setIsSearchField(true, false);
        try {
          broken.getSearchContainer().addView(new Object(), new Object());
          throw new AssertionError("Old order must reproduce the NPE");
        } catch (NullPointerException expected) { }
        for (int reopen = 0; reopen < 4; reopen++) {
          ActionBarMenuItem fixed = new ActionBarMenuItem().setIsSearchField(true, false);
          attach(fixed, new Object());
          fixed.getSearchField();
          if (fixed.creations != 1 || fixed.searchContainer.children != 1) throw new AssertionError();
        }
        System.out.println("Cold creation/recreation: old order NPE; fixed order PASS (JVM model)");
      }
    }'''
    with tempfile.TemporaryDirectory(prefix='pengram-settings-create-') as temp:
        source = Path(temp) / 'SettingsCreateCheck.java'
        source.write_text(code)
        subprocess.run([javac, '-d', temp, str(source)], check=True)
        subprocess.run([runtime, '-cp', temp, 'SettingsCreateCheck'], check=True)
else:
    print('JVM model SKIP: javac/java unavailable; source contracts PASS')
print('Settings search lazy initialisation contracts PASS; Android cold-open NOT RUN')
