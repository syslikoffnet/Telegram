#!/usr/bin/env python3
"""Popup structure, theme-null lifecycle and per-account child/auth ownership.
Host stubs exercise extracted production methods, not Android/device rendering.
"""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
java = root/'TMessagesProj/src/main/java/org/telegram'
def read(name): return (java/name).read_text()
def method(text, name):
    m = re.search(r'(?:public|private|protected)?\s*(?:static\s+)?[\w<>.]+\s+'+name+r'\([^{};]*\)\s*\{', text)
    assert m, name
    end=m.end(); depth=1
    while depth:
        depth += (text[end]=='{') - (text[end]=='}'); end+=1
    return re.sub(r'@NonNull\s*','',text[m.start():end])
menu=read('ui/Components/PengramChatMenuWrapper.java')
assert 'windowLayout.removeInnerViews();' in method(menu,'rebuild'), 'popup structural container is detached by removeAllViews'
assert 'windowLayout.removeAllViews();' not in method(menu,'rebuild')
input_source=read('ui/Components/chat/ChatInputViewsContainer.java')
draw=method(input_source,'dispatchDraw') # fadeView's override precedes the outer method; select it below
outer=input_source[input_source.index('private final Rect tmpRect'):]
draw=method(outer,'dispatchDraw')
assert 'underKeyboardBackgroundDrawable != null' in draw and 'blurredBackgroundDrawable != null' in draw
assert 'super.dispatchDraw(canvas);' in draw and 'drawFallback' in draw
for name in ('setInputIslandBubbleDrawable','setUnderKeyboardBackgroundDrawable','updateColors'):
    assert '!= null' in method(input_source,name), name
for name in ('checkInsets','checkInAppKeyboardViewHeight','checkInAppKeyboardChild'):
    assert 'windowInsetsProvider == null' in method(input_source,name),name
assert 'new ' not in draw and 'new ' not in method(outer,'drawFallback')
storage_check=method(read('messenger/MessagesStorage.java'),'checkSessionSlotEmpty')
assert 'storageQueue.postRunnable' in storage_check and 'AndroidUtilities.runOnUIThread' in storage_check
for table in ('dialogs','messages_v2','enc_chats','users','chats','contacts'):
    assert '"'+table+'"' in storage_check
assert 'cursor.dispose()' in storage_check and 'empty = false;' in storage_check
assert 'DELETE ' not in storage_check and 'cleanup(' not in storage_check
pager=read('ui/ViewPagerActivity.java')
assert 'bindChildAccount(fragment);' in pager, 'lazy child uses global selectedAccount instead of parent account'
assert pager.index('bindChildAccount(fragment);') < pager.index('fragment.onFragmentCreate();')
assert 'bindChildAccount(fragment);' in method(pager,'putFragmentAtPosition')
assert 'fragment.setCurrentAccount(currentAccount)' in method(pager,'bindChildAccount')
avatar=read('ui/Components/glass/GlassTabView.java')
assert 'getImageReceiver().setCurrentAccount(currentAccount)' in method(avatar,'createAvatar')
assert 'getImageReceiver().setCurrentAccount(currentAccount)' in method(avatar,'updateUserAvatar')
login=read('messenger/PengramSessionLogin.java')
assert 'full.users.get(0)' not in login, 'first response user is not necessarily the authenticated self'
assert 'resolveSessionSelf(full)' in login and 'user.self' in method(login,'resolveSessionSelf')
assert 'session.userId != 0 && session.userId != user.id' in login
assert 'hasAccountUserId(user.id, flow.account)' in login
assert 'checkSessionSlotEmpty(' in login and 'checkSessionSlotEmpty(' in read('messenger/MessagesStorage.java')
ui=read('ui/LoginActivity.java')
assert 'currentAccount != importedAccount' in ui
launch=read('ui/LaunchActivity.java')
assert 'hasAccountRoot(account)' in launch and 'getCurrentAccount() == account' in method(launch,'hasAccountRoot')
for locale in ('values','values-ru'):
    xml=(root/'TMessagesProj/src/main/res'/locale/'strings.xml').read_text()
    for key in ('PengramSessionErrIdentity','PengramSessionErrAccountExists'):
        assert 'name="'+key+'"' in xml

def run(command):
    result=subprocess.run(command,capture_output=True,text=True)
    assert result.returncode==0,result.stdout+result.stderr
    return result.stdout
jvm='SKIP(no JDK)'
fixture = r"""
import java.util.*;
public class Regression {
 static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
 static class TLRPC {
  static class User { long id; boolean self,deleted; User(long i,boolean s){id=i;self=s;} }
  static class UserFull {long id; User user;}
  static class TL_users_userFull {UserFull full_user; ArrayList<User> users=new ArrayList<>();}
 }
 SELF_METHOD
 static class View { void setOnClickListener(java.util.function.Consumer<View> c){} }
 static class Popup {
  boolean attached=true; int children;
  void removeAllViews(){attached=false;}
  void removeInnerViews(){children=0;}
  void addView(View v){children++;}
 }
 static class Swipe {void closeForeground(){}}
 static class R {static class drawable {static int msg_arrow_back=1;} static class string {static int Back=2;}}
 static class LocaleController {static String getString(int id){return "Back";}}
 static class FileLog {static void e(Throwable e){throw new AssertionError(e);}}
 static class PengramConfig {static int quickCount=2; static int getQuickActionCount(){return quickCount;}}
 static class ActionBarMenuItem {static View addItem(Popup p,int i,String s,boolean b,Object r){View v=new View();p.addView(v);return v;}}
 static class Menu {
  Popup windowLayout=new Popup(); Swipe swipeBackLayout=new Swipe(); Object resourcesProvider;
  ArrayList<Integer> items=new ArrayList<>(Arrays.asList(1,2,3));
  void addChatItem(int id){windowLayout.addView(new View());}
  void addQuickAction(int id){windowLayout.addView(new View());}
  MENU_METHOD
 }
 static class Child { int account=7; void setCurrentAccount(int a){account=a;} }
 static class Parent { int currentAccount=2; PARENT_METHOD }
 static class Path {}
 static class Rect {void set(int a,int b,int c,int d){} void inset(int a,int b){} void offset(int a,int b){}}
 static class RectF {void set(Rect r){}}
 static class Paint {static int ANTI_ALIAS_FLAG=1;int alpha=255;Paint(int f){}void setColor(int c){}void setAlpha(int a){alpha=a;}}
 static class Theme {interface ResourcesProvider{} static int key_chat_messagePanelBackground=1;static int getColor(int c,ResourcesProvider r){return c;}}
 static class Canvas {int draws,clips;void drawPath(Path p,Paint q){draws++;}void drawRoundRect(RectF r,float x,float y,Paint p){draws++;}void save(){}void restore(){}void clipPath(Path p){check(p!=null,"null clip path");clips++;}}
 static class BlurredBackgroundDrawable {
  int alpha=255;void setPadding(int a){}void setRadius(int a){}void setRadius(int a,int b,int c,int d){}
  void enableInAppKeyboardOptimization(){}void setThickness(int a){}void setIntensity(float a){}
  void updateColors(){}void setBounds(Rect r){}void setBounds(int a,int b,int c,int d){}
  void draw(Canvas c){c.draws++;} Path getPath(){return new Path();}void setAlpha(int a){alpha=a;}
 }
 static class Base {int dispatches,children;protected void dispatchDraw(Canvas c){dispatches++;}protected boolean drawChild(Canvas c,View v,long t){children++;return true;}int getMeasuredWidth(){return 300;}int getMeasuredHeight(){return 400;}void invalidate(){}}
 static class Sheen {void draw(Canvas c,Object v,Rect r){}}
 static class Input extends Base {
  static int INPUT_BUBBLE_RADIUS=22,INPUT_KEYBOARD_RADIUS=29;static int dp(int n){return n;}
  BlurredBackgroundDrawable underKeyboardBackgroundDrawable,blurredBackgroundDrawable;
  int inputBubbleAlpha=255,currentBlurredHeight=100,inputBubbleHeightRound=50;
  float imeBottomInset=40,inputBubbleOffsetLeft,inputBubbleOffsetRight,bubbleInputTranlationY;
  boolean drawInputBackground=true,needDrawInAppKeyboard;
  Rect tmpRect=new Rect();RectF tmpRectF=new RectF();Paint fallbackPaint=new Paint(1);
  Theme.ResourcesProvider fallbackResourcesProvider;Path underKeyboardPath=new Path();
  View inAppKeyboardBubbleContainer=new View();Sheen pengramSheen=new Sheen();
  INPUT_METHODS
 }
 public static void main(String[] args){
  int cases=0;
  TLRPC.TL_users_userFull f=new TLRPC.TL_users_userFull();f.full_user=new TLRPC.UserFull();f.full_user.id=20;
  TLRPC.User other=new TLRPC.User(10,false),self=new TLRPC.User(20,true);
  f.users.add(other);f.users.add(self);
  check(resolveSessionSelf(f)==self,"related first user selected instead of self");cases++;
  Collections.reverse(f.users);check(resolveSessionSelf(f)==self,"self first");cases++;
  self.self=false;check(resolveSessionSelf(f)==null,"non-self accepted");self.self=true;cases++;
  self.deleted=true;check(resolveSessionSelf(f)==null,"deleted self accepted");self.deleted=false;cases++;
  f.full_user.id=30;check(resolveSessionSelf(f)==null,"mismatching full_user.id accepted");f.full_user.id=20;cases++;
  f.users.add(new TLRPC.User(20,true));check(resolveSessionSelf(f)==null,"ambiguous self accepted");f.users.remove(2);cases++;
  f.users.clear();check(resolveSessionSelf(f)==null,"empty users accepted");cases++;
  f.full_user.user=self;f.full_user.id=0;check(resolveSessionSelf(f)==self,"legacy self rejected");cases++;
  f.full_user.id=10;check(resolveSessionSelf(f)==null,"legacy mismatch accepted");cases++;
  f.full_user=null;check(resolveSessionSelf(f)==null,"null full_user accepted");cases++;
  check(resolveSessionSelf(null)==null,"null response accepted");cases++;
  Menu m=new Menu();for(int n=0;n<20;n++){m.rebuild();check(m.windowLayout.attached&&m.windowLayout.children==6,"rebuild detached popup or duplicated buttons");cases++;}
  m.swipeBackLayout=null;PengramConfig.quickCount=0;m.rebuild();check(m.windowLayout.attached&&m.windowLayout.children==3,"items without back/quick");cases++;
  Parent parent=new Parent();Child child=new Child();parent.bindChildAccount(child);check(child.account==2,"global account 7 leaked into parent 2");cases++;
  for(int mask=0;mask<16;mask++){
   Input h=new Input();Canvas c=new Canvas();
   h.setInputIslandBubbleDrawable((mask&1)!=0?new BlurredBackgroundDrawable():null);
   h.setUnderKeyboardBackgroundDrawable((mask&2)!=0?new BlurredBackgroundDrawable():null);
   h.drawInputBackground=(mask&4)!=0;h.needDrawInAppKeyboard=(mask&8)!=0;
   h.updateColors();h.dispatchDraw(c);h.drawChild(c,h.inAppKeyboardBubbleContainer,0);
   check(h.dispatches==1&&h.children==1,"missing super/child draw");
   check(c.draws==((h.drawInputBackground?1:0)+(h.needDrawInAppKeyboard?1:0)),"missing drawable or theme fallback");
   check(c.clips==1,"keyboard clip missing");cases++;
   h.setInputIslandBubbleDrawable(null);h.setUnderKeyboardBackgroundDrawable(null);h.updateColors();h.dispatchDraw(c);cases++;
  }
  Input h=new Input();h.setInputBubbleAlpha(81);h.dispatchDraw(new Canvas());check(h.fallbackPaint.alpha==81,"fallback alpha lost");
  h.setInputIslandBubbleDrawable(new BlurredBackgroundDrawable());check(h.blurredBackgroundDrawable.alpha==81,"replacement alpha lost");cases+=2;
  System.out.println("OK: "+cases+" extracted production-method cases (host stubs, NOT Android rendering)");
 }
}
"""
fixture=fixture.replace('SELF_METHOD',method(login,'resolveSessionSelf'))
fixture=fixture.replace('MENU_METHOD',method(menu,'rebuild'))
fixture=fixture.replace('PARENT_METHOD',method(pager,'bindChildAccount').replace('BaseFragment','Child'))
fixture=fixture.replace('INPUT_METHODS','\n'.join([draw,method(outer,'drawChild'),method(outer,'drawFallback')]+[method(input_source,n) for n in ('setInputIslandBubbleDrawable','setUnderKeyboardBackgroundDrawable','updateColors','setInputBubbleAlpha')]))
flow_fixture = r"""
import java.util.*;
import java.security.MessageDigest;
public class SessionFlow {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static class TLRPC {
  static class User {long id;boolean self,deleted;User(long i,boolean s){id=i;self=s;}}
  static class UserFull {long id;User user;}
  static class TL_users_userFull {UserFull full_user=new UserFull();ArrayList<User> users=new ArrayList<>();}
  static class TL_auth_authorization {User user;}
  static class TL_error {String text;}
  static class TL_users_getFullUser {Object id;}
  static class TL_inputUserSelf {}
 }
 static class UserConfig {
  static int MAX_ACCOUNT_COUNT=4;static UserConfig[] slots;
  long uid;boolean loaded=true;static UserConfig getInstance(int a){return slots[a];}
  long getClientUserId(){return uid;}boolean isClientActivated(){return uid!=0;}boolean isConfigLoaded(){return loaded;}
 }
 static class PengramLocalKeep {static boolean[] local;static boolean isLocalOnly(int a){return local[a];}}
 static class PengramDiagnostics {static void recordImportAttempt(int a){}}
 static class PengramSessionFormats {
  static class Account {long userId;byte[] authKey=new byte[256];int dcId=2;boolean isUsable(){return authKey!=null&&authKey.length==256;}void clear(){Arrays.fill(authKey,(byte)0);}}
 }
 interface Result {void run(boolean empty);}
 static class Utilities {interface Callback<T> {void run(T value);}}
 static class Queue {void postRunnable(Runnable r){r.run();}}
 static class FileLog {static void e(Exception e){}}
 static class SQLiteCursor {boolean row,broken;static int disposed;SQLiteCursor(boolean r,boolean b){row=r;broken=b;}boolean next(){if(broken)throw new RuntimeException("read failure");return row;}void dispose(){disposed++;}}
 static class SQLiteDatabase {static Map<String,Boolean> rows=new HashMap<>();static boolean broken;int account;SQLiteDatabase(int a){account=a;}SQLiteCursor queryFinalized(String sql){String table=sql.split(" ")[3];return new SQLiteCursor(account==2&&(!MessagesStorage.empty[account]||Boolean.TRUE.equals(rows.get(table))),broken);}}
 static class MessagesStorage {
  static boolean[] empty;static MessagesStorage getInstance(int a){return new MessagesStorage(a);}int account;SQLiteDatabase database;Queue storageQueue=new Queue();MessagesStorage(int a){account=a;database=new SQLiteDatabase(a);}
  STORAGE_METHOD
 }
 static class AndroidUtilities {
  static ArrayDeque<Runnable> pending=new ArrayDeque<>();static void runOnUIThread(Runnable r){pending.add(r);}static void runOnUIThread(Runnable r,int ms){pending.add(r);}
  static void drain(){int n=0;while(!pending.isEmpty()){check(n++<100,"callback loop");pending.remove().run();}}
 }
 interface Response {void run(Object r,TLRPC.TL_error e);}
 static class ConnectionsManager {
  static int RequestFlagWithoutLogin=1,RequestFlagFailOnServerErrors=2,RequestFlagEnableUnauthorized=4;
  static ConnectionsManager[] instances;int cleanups,imports,account;long uid;Response response;
  ConnectionsManager(int a){account=a;}static ConnectionsManager getInstance(int a){return instances[a];}
  void cleanup(boolean b){cleanups++;uid=0;}void importAuthorization(int dc,byte[] k,long id){imports++;}
  void sendRequest(Object r,Response c,int flags){response=c;}void setUserId(long id){uid=id;}
  void deliver(Object r){response.run(r,null);AndroidUtilities.drain();}
 }
 static class Login {
  static int FLAGS=7;
  interface Listener {void onSuccess(TLRPC.TL_auth_authorization a);void onError(String code);}
  FLOW_CLASS
  static volatile Flow active;
  LOGIN_METHODS
 }
 static class Listener implements Login.Listener {
  int target,success;String error;Listener(int a){target=a;}
  public void onSuccess(TLRPC.TL_auth_authorization auth){success++;UserConfig.getInstance(target).uid=auth.user.id;}
  public void onError(String e){error=e;}
 }
 static void reset(){Login.cancel();AndroidUtilities.pending.clear();SQLiteDatabase.rows.clear();SQLiteDatabase.broken=false;SQLiteCursor.disposed=0;UserConfig.slots=new UserConfig[4];ConnectionsManager.instances=new ConnectionsManager[4];PengramLocalKeep.local=new boolean[4];MessagesStorage.empty=new boolean[]{true,true,true,true};for(int n=0;n<4;n++){UserConfig.slots[n]=new UserConfig();ConnectionsManager.instances[n]=new ConnectionsManager(n);}UserConfig.slots[0].uid=10;}
 static PengramSessionFormats.Account session(long id){PengramSessionFormats.Account a=new PengramSessionFormats.Account();a.userId=id;Arrays.fill(a.authKey,(byte)9);return a;}
 static TLRPC.TL_users_userFull reply(long id){TLRPC.TL_users_userFull f=new TLRPC.TL_users_userFull();f.full_user.id=id;f.users.add(new TLRPC.User(10,false));f.users.add(new TLRPC.User(id,true));return f;}
 static void begin(int a,PengramSessionFormats.Account s,String mode,Listener l){Login.start(a,s,mode,l);AndroidUtilities.drain();}
 static void safeMain(){check(UserConfig.slots[0].uid==10&&ConnectionsManager.instances[0].cleanups==0&&ConnectionsManager.instances[0].imports==0,"main slot changed");}
 public static void main(String[] args){
  int cases=0;reset();Listener l=new Listener(2);begin(2,session(0),"auto",l);ConnectionsManager.instances[2].deliver(reply(20));check(l.success==1&&UserConfig.slots[2].uid==20,"first related user installed");safeMain();cases++;
  reset();l=new Listener(2);begin(2,session(10),"auto",l);check("account_exists".equals(l.error)&&ConnectionsManager.instances[2].imports==0,"known duplicate imported");safeMain();cases++;
  reset();l=new Listener(2);begin(2,session(20),"auto",l);ConnectionsManager.instances[2].deliver(reply(30));check("identity_mismatch".equals(l.error)&&l.success==0&&UserConfig.slots[2].uid==0,"metadata mismatch committed");safeMain();cases++;
  reset();l=new Listener(2);begin(2,session(0),"auto",l);TLRPC.TL_users_userFull f=reply(10);f.users.remove(0);ConnectionsManager.instances[2].deliver(f);check("account_exists".equals(l.error)&&l.success==0,"unknown duplicate committed");safeMain();cases++;
  reset();l=new Listener(0);begin(0,session(20),"auto",l);check("slot_busy".equals(l.error),"occupied slot accepted");safeMain();cases++;
  reset();MessagesStorage.empty[2]=false;l=new Listener(2);begin(2,session(20),"auto",l);check("slot_busy".equals(l.error)&&ConnectionsManager.instances[2].imports==0,"old DB overwritten");safeMain();cases++;
  reset();l=new Listener(2);begin(2,session(20),"auto",l);UserConfig.slots[2].uid=30;ConnectionsManager.instances[2].deliver(reply(20));check("slot_busy".equals(l.error)&&UserConfig.slots[2].uid==30&&ConnectionsManager.instances[2].cleanups==1,"late reply cleaned occupied slot");safeMain();cases++;
  reset();Listener old=new Listener(2);begin(2,session(20),"auto",old);Response stale=ConnectionsManager.instances[2].response;Login.cancel();l=new Listener(2);begin(2,session(30),"auto",l);stale.run(reply(20),null);AndroidUtilities.drain();check(old.success==0&&l.success==0&&old.error==null,"cancelled callback committed");ConnectionsManager.instances[2].deliver(reply(30));check(l.success==1&&UserConfig.slots[2].uid==30,"replacement callback lost");safeMain();cases++;
  reset();PengramLocalKeep.local[2]=true;l=new Listener(2);begin(2,session(20),"auto",l);check("slot_busy".equals(l.error)&&ConnectionsManager.instances[2].imports==0,"local copy overwritten");safeMain();cases++;
  reset();l=new Listener(2);begin(2,session(20),"auto",l);f=reply(20);f.users.get(1).self=false;ConnectionsManager.instances[2].deliver(f);check("identity_mismatch".equals(l.error)&&l.success==0,"unverified self installed");safeMain();cases++;
  reset();UserConfig.slots[2].loaded=false;l=new Listener(2);begin(2,session(20),"auto",l);check("slot_busy".equals(l.error)&&ConnectionsManager.instances[2].imports==0,"unloaded config overwritten");safeMain();cases++;
  for(String table:new String[]{"dialogs","messages_v2","enc_chats","users","chats","contacts"}){
   reset();SQLiteDatabase.rows.put(table,true);l=new Listener(2);begin(2,session(20),"auto",l);check("slot_busy".equals(l.error)&&ConnectionsManager.instances[2].imports==0,"retained "+table+" erased");check(SQLiteCursor.disposed>0,"cursor leaked");safeMain();cases++;
  }
  reset();SQLiteDatabase.broken=true;l=new Listener(2);begin(2,session(20),"auto",l);check("slot_busy".equals(l.error)&&ConnectionsManager.instances[2].imports==0&&SQLiteCursor.disposed==1,"DB error was not fail-closed");safeMain();cases++;
  System.out.println("OK: "+cases+" extracted production import-flow cases (fake transport/storage, NOT device isolation)");
 }
}
"""
start=login.index('private static final class Flow {');end=login.index('    private static volatile Flow active;',start)
flow_fixture=flow_fixture.replace('FLOW_CLASS',login[start:end])
flow_fixture=flow_fixture.replace('STORAGE_METHOD',storage_check)
flow_fixture=flow_fixture.replace('LOGIN_METHODS','\n'.join(method(login,n) for n in ('authKeyId','start','isSlotAvailable','cancel','connect','resolveSessionSelf','hasAccountUserId','succeed','fail','mapError')))
if shutil.which('javac') and shutil.which('java'):
    with tempfile.TemporaryDirectory() as d:
        Path(d,'Regression.java').write_text(fixture)
        Path(d,'SessionFlow.java').write_text(flow_fixture)
        run(['javac','-d',d,str(Path(d,'Regression.java')),str(Path(d,'SessionFlow.java'))])
        print(run(['java','-cp',d,'Regression']),end='')
        print(run(['java','-cp',d,'SessionFlow']),end='')
        # Negative controls must fail; they do not replace device reproduction.
        for name, original, broken in (
            ('popup', 'windowLayout.removeInnerViews();', 'windowLayout.removeAllViews();'),
            ('theme', 'if (underKeyboardBackgroundDrawable != null) {', 'if (true) {'),
            ('child account', 'fragment.setCurrentAccount(currentAccount);', 'fragment.setCurrentAccount(7);'),
            ('self selection', 'if (full == null || full.full_user == null) return null;', 'if (full != null) return full.users.get(0);')
        ):
            assert original in fixture,name
            Path(d,'Regression.java').write_text(fixture.replace(original,broken,1))
            run(['javac','-d',d,str(Path(d,'Regression.java'))])
            failed=subprocess.run(['java','-cp',d,'Regression'],capture_output=True,text=True)
            assert failed.returncode!=0,'negative control survived: '+name
            print('RED negative control: '+name)
    jvm='PASS(67 component + 18 import/storage-flow cases)'
print('OK: popup structure, null-theme fallback, parent account and verified session self; JVM='+jvm)
