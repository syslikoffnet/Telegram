package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramProfileHistory;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Date-grouped readable delta timeline for one user. */
public class PengramProfileHistoryActivity extends BaseFragment {
    private final long userId;
    private LinearLayout list;
    public PengramProfileHistoryActivity(long userId){this.userId=userId;}
    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setTitle(getString(R.string.PengramProfileHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView scroll=new ScrollView(context);scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));list=new LinearLayout(context);list.setPadding(dp(12),dp(10),dp(12),dp(20));list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);fragmentView=scroll;load();return fragmentView;
    }
    private void load(){org.telegram.messenger.Utilities.globalQueue.postRunnable(()->{ArrayList<PengramProfileHistory.Change> values=PengramProfileHistory.getChanges(userId);AndroidUtilities.runOnUIThread(()->show(values));});}
    private void show(ArrayList<PengramProfileHistory.Change> values){list.removeAllViews();if(values.isEmpty()){TextView e=text(getString(R.string.PengramProfileHistoryEmpty),14);e.setGravity(Gravity.CENTER);list.addView(e,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,120));return;}String last="";for(PengramProfileHistory.Change c:values){String day=LocaleController.formatDateChat((int)c.time);if(!day.equals(last)){last=day;TextView h=text(day,13);h.setTypeface(Typeface.DEFAULT_BOLD);h.setGravity(Gravity.CENTER);list.addView(h,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,36));}LinearLayout card=new LinearLayout(getContext());card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(10),dp(14),dp(10));card.setBackground(Theme.createRoundRectDrawable(dp(14),Theme.getColor(Theme.key_windowBackgroundWhite)));StringBuilder b=new StringBuilder();if(c.name!=null)b.append("Name: ").append(c.name).append('\n');if(c.username!=null)b.append("Username: @").append(c.username).append('\n');if(c.bio!=null)b.append("Bio: ").append(c.bio).append('\n');if(c.avatarHash!=null)b.append(getString(R.string.PengramProfileAvatarChanged)).append('\n');b.append(LocaleController.formatDateTime((int)c.time,true));TextView t=text(b.toString(),15);card.addView(t);list.addView(card,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,LayoutHelper.WRAP_CONTENT,0,0,0,8));}}
    private TextView text(CharSequence s,int size){TextView v=new TextView(getContext());v.setText(s);v.setTextSize(size);v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));return v;}
}
