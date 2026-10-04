package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramProfileHistory;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;

/** Date-grouped profile snapshots reconstructed from compact database deltas. */
public class PengramProfileHistoryActivity extends BaseFragment {
    /** сколько карточек рисуем за раз — иначе длинная история вешает UI-поток */
    private static final int RENDER_LIMIT = 80;
    /** маленькие аватарки живут в кэше: BitmapFactory на каждый кадр списка — это OOM и фризы */
    private static final android.util.LruCache<String, android.graphics.Bitmap> avatarCache =
            new android.util.LruCache<String, android.graphics.Bitmap>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };

    private final long userId;
    private LinearLayout list;
    private String query = "";
    private int renderLimit = RENDER_LIMIT;
    private ArrayList<PengramProfileHistory.Change> lastValues = new ArrayList<>();
    public PengramProfileHistoryActivity(long userId){this.userId=userId;}

    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setTitle(getString(R.string.PengramProfileHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ActionBarMenuItem search=actionBar.createMenu().addItem(1,R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener(){
            @Override public void onSearchCollapse(){query="";load();}
            @Override public void onTextChanged(android.widget.EditText editText){query=editText.getText().toString();load();}
        });
        search.setSearchFieldHint(getString(R.string.Search));
        ScrollView scroll=new ScrollView(context);scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        list=new LinearLayout(context);list.setPadding(dp(12),dp(10),dp(12),dp(20));list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);fragmentView=scroll;load();return fragmentView;
    }

    private void load(){final String requested=query;renderLimit=RENDER_LIMIT;org.telegram.messenger.Utilities.globalQueue.postRunnable(()->{
        ArrayList<PengramProfileHistory.Change> values=reconstruct(PengramProfileHistory.getChanges(userId));
        if(!TextUtils.isEmpty(requested)){String q=requested.toLowerCase(Locale.ROOT);values.removeIf(c->!searchText(c).toLowerCase(Locale.ROOT).contains(q));}
        AndroidUtilities.runOnUIThread(()->{if(TextUtils.equals(requested,query))show(values);});
    });}

    /** Walk oldest→newest per user and materialize the complete state for every delta. */
    private ArrayList<PengramProfileHistory.Change> reconstruct(ArrayList<PengramProfileHistory.Change> deltas){
        Collections.reverse(deltas);HashMap<String,PengramProfileHistory.Change> states=new HashMap<>();ArrayList<PengramProfileHistory.Change> snapshots=new ArrayList<>();
        for(PengramProfileHistory.Change d:deltas){String key=d.account+":"+d.userId;PengramProfileHistory.Change previous=states.get(key);PengramProfileHistory.Change s=new PengramProfileHistory.Change();s.id=d.id;s.account=d.account;s.userId=d.userId;s.time=d.time;s.firstSnapshot=previous==null;s.previousName=d.name!=null&&previous!=null?previous.name:null;s.previousUsername=d.username!=null&&previous!=null?previous.username:null;s.previousBio=d.bio!=null&&previous!=null?previous.bio:null;s.previousAvatarHash=d.avatarHash!=null&&previous!=null?previous.avatarHash:null;s.name=d.name!=null?d.name:previous==null?null:previous.name;s.username=d.username!=null?d.username:previous==null?null:previous.username;s.bio=d.bio!=null?d.bio:previous==null?null:previous.bio;s.avatarHash=d.avatarHash!=null?d.avatarHash:previous==null?null:previous.avatarHash;states.put(key,s);snapshots.add(s);}
        Collections.reverse(snapshots);return snapshots;
    }
    private String searchText(PengramProfileHistory.Change c){return c.userId+" "+safe(c.name)+" "+safe(c.username)+" "+safe(c.bio);}
    private String safe(String s){return s==null?"":s;}

    private void show(ArrayList<PengramProfileHistory.Change> values){
        lastValues=values;
        list.removeAllViews();if(values.isEmpty()){TextView e=text(getString(R.string.PengramProfileHistoryEmpty),14);e.setGravity(Gravity.CENTER);list.addView(e,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,120));return;}
        final int limit=Math.min(values.size(),Math.max(RENDER_LIMIT,renderLimit));
        String last="";for(int index=0;index<limit;index++){final PengramProfileHistory.Change c=values.get(index);String day=LocaleController.formatDateChat((int)c.time);if(!day.equals(last)){last=day;TextView h=text(day,13);h.setTypeface(Typeface.DEFAULT_BOLD);h.setGravity(Gravity.CENTER);list.addView(h,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,36));}
            LinearLayout card=new LinearLayout(getContext());card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.TOP);card.setPadding(dp(12),dp(10),dp(12),dp(10));card.setBackground(Theme.createRoundRectDrawable(dp(14),Theme.getColor(Theme.key_windowBackgroundWhite)));
            File avatar=PengramProfileHistory.avatarFile(c.avatarHash);android.graphics.Bitmap thumb=avatar!=null&&avatar.exists()?avatarThumb(c.avatarHash,avatar):null;if(thumb!=null){ImageView image=new ImageView(getContext());image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setClipToOutline(true);image.setBackground(Theme.createRoundRectDrawable(dp(24),0xffdddddd));image.setImageBitmap(thumb);card.addView(image,LayoutHelper.createLinear(52,52,0,0,12,0));}
            StringBuilder b=new StringBuilder();b.append("#user_").append(c.userId).append("\nID: ").append(c.userId).append("\nAccount: ").append(c.account+1).append('\n');if(c.firstSnapshot)b.append(getString(R.string.PengramProfileFirstSaved)).append('\n');appendChange(b,"Name",c.previousName,c.name,false);appendChange(b,"Usernames",c.previousUsername,c.username,true);appendChange(b,"Bio",c.previousBio,c.bio,false);if(c.avatarHash!=null){if(c.previousAvatarHash!=null)b.append("Avatar: #avatar_").append(c.previousAvatarHash).append(" → #avatar_").append(c.avatarHash).append('\n');else b.append("Avatar: #avatar_").append(c.avatarHash).append('\n');}b.append(LocaleController.formatDateTime((int)c.time,true));TextView t=text(b.toString(),15);card.addView(t,LayoutHelper.createLinear(0,LayoutHelper.WRAP_CONTENT,1f));list.addView(card,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,0,0,0,8));
        }
        if(values.size()>limit){
            TextView more=text(LocaleController.formatString(R.string.PengramHistoryShowMore,values.size()-limit),15);
            more.setGravity(Gravity.CENTER);
            more.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            more.setBackground(Theme.createRoundRectDrawable(dp(14),Theme.getColor(Theme.key_windowBackgroundWhite)));
            more.setOnClickListener(v->{renderLimit=limit+RENDER_LIMIT;show(lastValues);});
            list.addView(more,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,44,0,0,0,8));
        }
    }

    /** уменьшенная аватарка под размер карточки: полноразмерные битмапы тут не нужны */
    private android.graphics.Bitmap avatarThumb(String hash,File file){
        final String key=hash==null?file.getAbsolutePath():hash;
        android.graphics.Bitmap cached=avatarCache.get(key);
        if(cached!=null&&!cached.isRecycled()){
            return cached;
        }
        try{
            final int target=dp(52);
            final BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
            int sample=1;
            while(bounds.outWidth/(sample*2)>=target&&bounds.outHeight/(sample*2)>=target){
                sample*=2;
            }
            final BitmapFactory.Options options=new BitmapFactory.Options();
            options.inSampleSize=sample;
            final android.graphics.Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            if(bitmap!=null){
                avatarCache.put(key,bitmap);
            }
            return bitmap;
        }catch(Throwable e){
            return null;
        }
    }
    private void appendChange(StringBuilder out,String label,String previous,String current,boolean username){
        if(current==null)return;String now=formatValue(current,username);out.append(label).append(": ");
        if(previous!=null&&!TextUtils.equals(previous,current))out.append(formatValue(previous,username)).append(" → ");
        out.append(now).append('\n');
    }
    private String formatValue(String value,boolean username){if(TextUtils.isEmpty(value))return "—";return username?"@"+value:value;}
    private TextView text(CharSequence s,int size){TextView v=new TextView(getContext());v.setText(s);v.setTextSize(size);v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));return v;}
}
