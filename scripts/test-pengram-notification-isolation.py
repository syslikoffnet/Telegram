#!/usr/bin/env python3
"""Account routing contracts and real pure-Java resolver tests; NOT device tests."""
from pathlib import Path
import shutil, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
j=root/'TMessagesProj/src/main/java/org/telegram/messenger'
n=(j/'NotificationsController.java').read_text()
p=(j/'PushListenerController.java').read_text()
assert 'notificationManager.notify(notificationGroup, id, notification.build());' in n, 'same peer notification overwrites another account'
repeat=n.split('private void scheduleNotificationRepeat()',1)[1].split('private boolean isPersonalMessage',1)[0]
assert 'PendingIntent.getService(ApplicationLoader.applicationContext, currentAccount, intent,' in repeat
assert 'PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT' in repeat
assert n.count('PendingIntent.getBroadcast(')==1, 'broadcast PendingIntents must all use account identity'
assert 'PengramNotificationRouting.broadcastIdentity(' in n
assert 'PengramNotificationRouting.findPushAccount(userIdObject)' in p
assert 'Utilities.parseInt((String) userIdObject)' not in p
assert 'notificationManager.cancel(notificationGroup, id);' in n
cleanup=n.split('public void cleanup()',1)[1].split('\n    }',1)[0]
assert 'wearNotificationsIds.clear()' not in cleanup.split('dismissNotification();',1)[0], 'child ids lost before cancel'
dismiss=n.split('private void dismissNotification()',1)[1].split('\n    }',1)[0]
assert dismiss.index('notificationManager.cancel(notificationGroup, wearNotificationsIds.valueAt(a))') < dismiss.index('wearNotificationsIds.clear()')

diag=(j/'PengramDiagnostics.java').read_text()
assert 'verifyAccountIdentities' not in diag and 'sendRequest(' not in diag
shortcut=n.split('private String createNotificationShortcut(',1)[1].split('ShortcutInfoCompat.Builder shortcutBuilder',1)[0]
assert 'shortcutIntent.putExtra("currentAccount", currentAccount);' in shortcut
assert 'PengramNotificationRouting.shortcutIdentity(' in shortcut
assert 'notificationManager.cancel(wearNotificationsIds.valueAt(a))' not in n
s=(j/'PengramNotificationRouting.java').read_text()
assert 'UserConfig.selectedAccount' not in s
assert 'Long.parseLong' in s
assert 'return -1;' in s
fixture=r'''
class PengramLocalKeep {static boolean[] local=new boolean[4];static boolean isLocalOnly(int a){return local[a];}}
class UserConfig {
 static final int MAX_ACCOUNT_COUNT=4;static UserConfig[] all=new UserConfig[4];
 long id;boolean active=true;UserConfig(long id){this.id=id;}
 static UserConfig getInstance(int a){return all[a];}
 long getClientUserId(){return id;}boolean isClientActivated(){return active&&id>0;}
}
public class Regression {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  for(int i=0;i<4;i++)UserConfig.all[i]=new UserConfig(0);
  UserConfig.all[0].id=123;UserConfig.all[2].id=8000000001L;
  check(PengramNotificationRouting.findPushAccount("8000000001")==2,"64-bit string truncated");
  check(PengramNotificationRouting.findPushAccount(8000000001L)==2,"long");
  check(PengramNotificationRouting.findPushAccount(123)==0,"integer");
  check(PengramNotificationRouting.findPushAccount(null)==-1,"ambiguous legacy routed to selected");
  for(Object bad:new Object[]{"bad", "123abc", "9223372036854775808", -1, 0, 123.0, true, "0", "-1"})
   check(PengramNotificationRouting.findPushAccount(bad)==-1,"invalid recipient: "+bad);
  check(PengramNotificationRouting.findPushAccount(999L)==-1,"unknown recipient");
  UserConfig.all[1].id=123;check(PengramNotificationRouting.findPushAccount(123)==-1,"duplicate identity routed to first account");
  PengramLocalKeep.local[1]=true;check(PengramNotificationRouting.findPushAccount(123)==0,"local copy steals push");
  UserConfig.all[0].active=false;check(PengramNotificationRouting.findPushAccount(123)==-1,"local copy selected as network account");
  UserConfig.all[0].active=true;PengramLocalKeep.local[1]=false;
  UserConfig.all[1].id=0;UserConfig.all[2].active=false;
  check(PengramNotificationRouting.findPushAccount(null)==0,"single-account legacy rejected");
  check(PengramNotificationRouting.findPushAccount(8000000001L)==-1,"inactive account");
  String a=PengramNotificationRouting.broadcastIdentity(0,123,777000,0,1);
  String b=PengramNotificationRouting.broadcastIdentity(2,8000000001L,777000,0,1);
  check(!a.equals(b),"same receiver/requestCode/dialog collides across accounts");
  check(!a.equals(PengramNotificationRouting.broadcastIdentity(0,124,777000,0,1)),"slot reuse collides");
  check(!a.equals(PengramNotificationRouting.broadcastIdentity(0,123,777000,1,1)),"topic collision");
  check(!a.equals(PengramNotificationRouting.broadcastIdentity(0,123,777000,0,2)),"callback message collision");
  check(a.equals(PengramNotificationRouting.broadcastIdentity(0,123,777000,0,1)),"identity not stable");
  check(!PengramNotificationRouting.shortcutIdentity(0,123,777000).equals(PengramNotificationRouting.shortcutIdentity(2,8000000001L,777000)),"shortcut account collision");
  check(!PengramNotificationRouting.shortcutIdentity(0,123,777000).equals(PengramNotificationRouting.shortcutIdentity(0,124,777000)),"shortcut slot reuse");
  System.out.println("OK: production resolver/identity, 26 cases; host Java, NOT Android PendingIntent rendering");
 }
}
'''
if shutil.which('java') and shutil.which('javac'):
 with tempfile.TemporaryDirectory() as d:
  Path(d,'PengramNotificationRouting.java').write_text(s.replace('package org.telegram.messenger;',''))
  Path(d,'Regression.java').write_text(fixture)
  for command in (['javac','-d',d,str(Path(d,'Regression.java')),str(Path(d,'PengramNotificationRouting.java'))],['java','-cp',d,'Regression']):
   result=subprocess.run(command,capture_output=True,text=True)
   assert result.returncode==0,result.stdout+result.stderr
   print(result.stdout,end='')
else: print('JVM SKIP: no local JDK')
print('OK: tagged notifications, account-scoped broadcasts and explicit push recipient')
