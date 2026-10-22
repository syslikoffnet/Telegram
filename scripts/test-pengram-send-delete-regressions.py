#!/usr/bin/env python3
"""Send overlay ownership and IME deletion regressions; not Android instrumentation."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'
container = (java/'ui/MessageEnterTransitionContainer.java').read_text()
enter = (java/'ui/TextMessageEnterTransition.java').read_text()
effects = (java/'ui/Components/PengramTypingEffects.java').read_text()
composer = (java/'ui/Components/ChatActivityEnterView.java').read_text()
def method(text, name):
    m = re.search(r'(?:public|private|protected)?\s*(?:static\s+)?\w+\s+'+name+r'\([^{};]*\)\s*\{', text)
    assert m, name
    end=m.end(); depth=1
    while depth:
        depth += (text[end]=='{') - (text[end]=='}'); end+=1
    return text[m.start():end]
visibility = method(container, 'checkVisibility')
assert 'else if (!transitions.isEmpty())' in visibility, 'active overlay must cancel pending hide even when already VISIBLE'
hide = re.search(r'Runnable hideRunnable = .*;', container)[0]
assert 'transitions.isEmpty()' in hide, 'already-dequeued idle hide must recheck ownership'
assert 'finishTransition();' in enter.split('if (messageView.getMessageObject().stableId != messageId)',1)[1].split('}',1)[0]
assert 'TextUtils.isEmpty(AndroidUtilities.trim(editText, null))' in enter, 'empty composer must not own message text'
assert 'effectsEnabled()' in method(effects,'live')
enabled=method(effects,'effectsEnabled')
assert 'isTypingDeleteAnim()' in enabled and 'isTypingCursorSmooth()' in enabled
capture=method(effects,'captureBefore')
assert 'composing(edit)' not in capture, 'IME deletion needs the pre-edit glyph snapshot'
assert 'count > after' in composer and '(!pengramImeComposing || before > count)' in composer
spawn=method(effects,'spawnFromSnapshot')
assert 'removedOffsets[i] < start' in spawn and 'removedClusters[i].length() > start + before' in spawn
assert 'removedStart != start' not in spawn, 'normalized IME subrange must be accepted'
assert 'emitted <' in spawn, 'density cap must apply after selecting the deleted range'
assert 'removedOffsets[boundaryCount % MAX_DELETE_CLUSTERS]' in method(effects,'snapshot')
assert 'rasterizeSnapshot(edit, layout, cursor)' in method(effects,'snapshot'), 'deleted glyph near cursor must get the limited raster budget first'
assert 'composing(edit)' in method(effects,'applyUserEdit'), 'composition must not animate inserted text prematurely'
preview=(java/'ui/Components/PengramTypingPreviewView.java').read_text()
assert 'captureReplacement(input' in preview and 'count > after' in preview
assert 'count, suppress, compositionEdit)' in preview
assert 'isTypingDeleteAnim()' in preview
assert 'PengramTypingEffects.clear(input)' not in method(preview,'setComposingRegion'), 'IME region update must not erase live deletion particles'
# Run the actual production visibility method and hide callback against a fake idle scheduler.
def run_java(command):
    result = subprocess.run(command, capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
jvm='SKIP(no JDK)'
if shutil.which('javac') and shutil.which('java'):
    with tempfile.TemporaryDirectory() as d:
        p=Path(d)
        source='''import java.util.ArrayList;
public class OverlayHarness {
static class View { static final int GONE=8, VISIBLE=0; }
static class NotificationCenter {
 static final NotificationCenter instance=new NotificationCenter(); Runnable queued;
 static NotificationCenter getInstance(int a){return instance;}
 void removeDelayed(Runnable r){if(queued==r)queued=null;}
 void doOnIdle(Runnable r){queued=r;}
}
final int currentAccount=0; int visibility=View.VISIBLE;
final ArrayList<Object> transitions=new ArrayList<>();
int getVisibility(){return visibility;} void setVisibility(int v){visibility=v;}
'''+hide+'\n'+visibility+'''
public static void main(String[] args){
 for(int i=0;i<100;i++){
  OverlayHarness h=new OverlayHarness(); h.checkVisibility();
  Runnable old=NotificationCenter.instance.queued;
  h.transitions.add(new Object()); h.checkVisibility();
  if(NotificationCenter.instance.queued!=null)throw new AssertionError("pending hide retained");
  old.run(); if(h.visibility!=View.VISIBLE)throw new AssertionError("active overlay hidden");
  h.transitions.clear(); h.checkVisibility(); NotificationCenter.instance.queued.run();
  if(h.visibility!=View.GONE)throw new AssertionError("idle overlay not hidden");
 }
}
}'''
        (p/'OverlayHarness.java').write_text(source)
        run_java(['javac',str(p/'OverlayHarness.java')])
        run_java(['java','-cp',d,'OverlayHarness'])
        particles = r'''import java.util.*;
import java.lang.ref.WeakReference;
public class ParticleHarness {
static final int MAX_PARTICLES=160, PARTICLE_LIFE=620;
static class PengramConfig {
 static final int DELETE_PARTICLE_LETTERS=0,DELETE_PARTICLE_DUST=1,DELETE_PARTICLE_SNOW=2,DELETE_PARTICLE_PETALS=3,DELETE_PARTICLE_SPARKS=4;
 static int getTypingDeleteCount(){return 1;} static int getTypingDeleteSpeed(){return 50;}
 static int getTypingDeleteSpread(){return 50;} static int getTypingDeleteSize(){return 50;}
 static int getTypingDeleteStyle(){return DELETE_PARTICLE_LETTERS;}
}
static class EditText {float getTextSize(){return 16;} void invalidate(){}}
static class Layout {}
static class Bitmap {int getWidth(){return 10;}}
static class Raster {Bitmap bitmap=new Bitmap();int uses;}
static class SystemClock {static long uptimeMillis(){return 1000;}}
static class Particle {
 int style; String text; Raster raster;
 float x,y,scrollX,scrollY,topDelta,vx,vy,gravity,size,scale,rotationSpeed,seed;
 long start,duration;
}
EditText edit=new EditText(); WeakReference<EditText> ref=new WeakReference<>(edit);
int removedStart,removedUnits,removedCount,removedScrollX,removedScrollY;
String[] removedClusters=new String[24]; int[] removedOffsets=new int[24];
Raster[] removedRasters=new Raster[24];
float[] removedX=new float[24],removedBaseline=new float[24],removedTop=new float[24],removedWidth=new float[24],removedDirection=new float[24];
Random random=new Random(1); ArrayList<Particle> particles=new ArrayList<>();
void releaseSnapshot(){removedCount=0;} void scheduleAfter(long d){}
int rasterBudget=1000;
boolean glyphRect(EditText e,Layout l,int start,int end){return true;}
Raster raster(EditText e,Layout l,int start,int end,String cluster){return rasterBudget-- > 0 ? new Raster() : null;}

'''+method(effects,'spawnFromSnapshot')+'\n'+method(effects,'rasterizeSnapshot')+r'''
void fill(int start,int units,int first,int n){
 removedStart=start; removedUnits=units; removedCount=n;
 for(int i=0;i<n;i++){removedOffsets[i]=first+i;removedClusters[i]="x";removedRasters[i]=new Raster();removedWidth[i]=10;removedDirection[i]=1;}
}
public static void main(String[] args){
 // A nearly full bitmap cache must prioritize the actual backspace glyph over the old prefix.
 ParticleHarness h=new ParticleHarness();h.fill(0,40,16,24);Arrays.fill(h.removedRasters,null);h.rasterBudget=1;
 h.rasterizeSnapshot(h.edit,new Layout(),40);h.spawnFromSnapshot(39,1);
 if(h.particles.size()!=1 || h.particles.get(0).style!=PengramConfig.DELETE_PARTICLE_LETTERS)throw new AssertionError("deleted letter lost raster priority");
 // IME rewrites a long word. The density cap must not discard its deleted tail.
 h=new ParticleHarness();h.fill(0,40,16,24);h.spawnFromSnapshot(39,1);
 if(h.particles.size()!=1)throw new AssertionError("IME tail lost");
 h=new ParticleHarness();h.fill(5,2,5,2);h.spawnFromSnapshot(5,2);
 if(h.particles.size()!=2)throw new AssertionError("selection delete lost");
 h=new ParticleHarness();h.fill(0,40,16,24);h.spawnFromSnapshot(40,1);
 if(!h.particles.isEmpty())throw new AssertionError("stale range accepted");
 for(String cluster:new String[]{"й","بَ","👩‍👩‍👧‍👦"}){
  h=new ParticleHarness();h.fill(0,28+cluster.length(),28,1);h.removedClusters[0]=cluster;
  h.spawnFromSnapshot(28,cluster.length());
  if(h.particles.size()!=1 || !cluster.equals(h.particles.get(0).text))throw new AssertionError("cluster split");
  h=new ParticleHarness();h.fill(0,28+cluster.length(),28,1);h.removedClusters[0]=cluster;
  h.spawnFromSnapshot(28,cluster.length()-1);
  if(!h.particles.isEmpty())throw new AssertionError("partial cluster animated");
 }
}
}'''
        (p/'ParticleHarness.java').write_text(particles)
        run_java(['javac','-encoding','UTF-8',str(p/'ParticleHarness.java')])
        run_java(['java','-cp',d,'ParticleHarness'])
        jvm='PASS(100 scheduler cases + 10 IME/range/cluster/cache-priority cases; production methods, fake Android)'
print('OK: send overlay ownership, empty-source fallback, independent delete effect and IME subranges; JVM='+jvm)
