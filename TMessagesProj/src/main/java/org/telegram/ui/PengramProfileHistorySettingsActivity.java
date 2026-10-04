package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramProfileHistory;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;

/** Privacy-first configuration for the profile delta journal. */
public class PengramProfileHistorySettingsActivity extends BaseFragment {
    private LinearLayout list;
    @Override public View createView(Context context){
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);actionBar.setTitle(getString(R.string.PengramProfileHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick(){@Override public void onItemClick(int id){if(id==-1)finishFragment();}});
        ScrollView scroll=new ScrollView(context);scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);fragmentView=scroll;rebuild();return fragmentView;
    }
    private void rebuild(){if(list==null)return;list.removeAllViews();
        addEnabledSwitch();
        if(!PengramProfileHistory.enabled()) { addInfo(R.string.PengramProfileHistoryDisabledInfo); return; }
        addChoice(R.string.PengramProfileHistoryStorage,storageName(),this::showStorageChoice);
        if(PengramConfig.getIntCached(PengramProfileHistory.KEY_STORAGE,0)!=PengramProfileHistory.STORAGE_LOCAL){addChoice(R.string.PengramProfileCloudFormat,cloudFormatName(),this::showCloudFormatChoice);addAction(R.string.PengramProfileCloudRecreate,()->new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileCloudRecreate)).setMessage(getString(R.string.PengramProfileCloudRecreateInfo)).setPositiveButton(getString(R.string.OK),(d,w)->org.telegram.messenger.PengramProfileCloud.recreateChannel(currentAccount)).setNegativeButton(getString(R.string.Cancel),null).show());}
        addChoice(R.string.PengramProfileHistoryScope,scopeName(),this::showScopeChoice);
        addCheck(R.string.PengramProfileHistoryBio,PengramProfileHistory.KEY_BIO,true,null);
        addCheck(R.string.PengramProfileHistoryAvatars,PengramProfileHistory.KEY_AVATARS,false,null);
        addChoice(R.string.PengramProfileLimitSize, sizeLimitName(), this::showSizeLimitChoice);
        addChoice(R.string.PengramProfileLimitTime, timeLimitName(), this::showTimeLimitChoice);
        addInfo(R.string.PengramProfileHistoryRiskInfo);
        addAction(R.string.PengramProfileHistoryOpen,()->presentFragment(new PengramProfileHistoryActivity(0)));
        addAction(R.string.PengramProfileHistoryClearAvatars,()->new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileHistoryClearAvatars)).setMessage(getString(R.string.PengramProfileHistoryClearAvatarsInfo)).setPositiveButton(getString(R.string.Delete),(d,w)->PengramProfileHistory.clearAvatars()).setNegativeButton(getString(R.string.Cancel),null).show());
        addInfo(R.string.PengramProfileHistoryRetentionInfo);
    }
    private void addEnabledSwitch(){TextCheckCell c=new TextCheckCell(getContext());c.setTextAndCheck(getString(R.string.PengramProfileHistoryEnable),PengramProfileHistory.enabled(),false);c.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));c.setOnClickListener(v->{if(PengramProfileHistory.enabled())showDisableChoice();else showStorageChoice();});list.addView(c,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,50));}
    private void addCheck(int title,String key,boolean def,java.util.function.Consumer<Boolean> after){TextCheckCell c=new TextCheckCell(getContext());c.setTextAndCheck(getString(title),PengramConfig.getBool(key,def),false);c.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));c.setOnClickListener(v->{boolean n=!PengramConfig.getBool(key,def);PengramConfig.setBool(key,n);c.setChecked(n);if(after!=null)after.accept(n);rebuild();});list.addView(c,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,50));}
    private void addChoice(int title,String value,Runnable click){TextSettingsCell c=new TextSettingsCell(getContext());c.setTextAndValue(getString(title),value,false);c.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));c.setOnClickListener(v->click.run());list.addView(c,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,56));}
    private void addAction(int title,Runnable click){TextSettingsCell c=new TextSettingsCell(getContext());c.setText(getString(title),false);c.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));c.setOnClickListener(v->click.run());list.addView(c,LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,50));}
    private void addInfo(int text){TextView v=new TextView(getContext());v.setText(getString(text));v.setTextSize(13);v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));v.setPadding(dp(21),dp(10),dp(21),dp(14));list.addView(v);}
    private String storageName(){return getString(new int[]{R.string.PengramProfileStorageLocal,R.string.PengramProfileStorageCloud,R.string.PengramProfileStorageBoth}[PengramConfig.getIntCached(PengramProfileHistory.KEY_STORAGE,0)]);}
    private String scopeName(){return getString(new int[]{R.string.PengramProfileScopeManual,R.string.PengramProfileScopeInteracted,R.string.PengramProfileScopeContacts,R.string.PengramProfileScopeAll}[PengramConfig.getIntCached(PengramProfileHistory.KEY_SCOPE,0)]);}
    private String cloudFormatName(){return getString(new int[]{R.string.PengramProfileCloudDelta,R.string.PengramProfileCloudBatch,R.string.PengramProfileCloudSummary}[PengramConfig.getIntCached(PengramProfileHistory.KEY_CLOUD_FORMAT,0)]);}
    private void showCloudFormatChoice(){String[] a={getString(R.string.PengramProfileCloudDelta),getString(R.string.PengramProfileCloudBatch),getString(R.string.PengramProfileCloudSummary)};new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileCloudFormat)).setItems(a,(d,w)->{PengramConfig.setIntValue(PengramProfileHistory.KEY_CLOUD_FORMAT,w);org.telegram.messenger.PengramProfileCloud.schedule(currentAccount);rebuild();}).show();}
    private static final int[] SIZE_LIMITS={100,250,500,1024,2048,5120,0};
    private static final int[] TIME_LIMITS={30,90,180,365,0};
    private String sizeName(int mb){return mb==0?getString(R.string.PengramProfileUnlimited):(mb>=1024?(mb/1024)+" GB":mb+" MB");}
    private String timeName(int days){return days==0?getString(R.string.PengramProfileUnlimited):days+" "+getString(R.string.PengramProfileDays);}
    private String sizeLimitName(){return sizeName(PengramConfig.getIntCached(PengramProfileHistory.KEY_LIMIT_MB,1024));}
    private String timeLimitName(){return timeName(PengramConfig.getIntCached(PengramProfileHistory.KEY_LIMIT_DAYS,0));}
    private void showSizeLimitChoice(){String[] a=new String[SIZE_LIMITS.length];for(int i=0;i<a.length;i++)a[i]=sizeName(SIZE_LIMITS[i]);new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileLimitSize)).setItems(a,(d,w)->{PengramConfig.setIntValue(PengramProfileHistory.KEY_LIMIT_MB,SIZE_LIMITS[w]);PengramProfileHistory.applyRetentionNow();rebuild();}).show();}
    private void showTimeLimitChoice(){String[] a=new String[TIME_LIMITS.length];for(int i=0;i<a.length;i++)a[i]=timeName(TIME_LIMITS[i]);new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileLimitTime)).setItems(a,(d,w)->{PengramConfig.setIntValue(PengramProfileHistory.KEY_LIMIT_DAYS,TIME_LIMITS[w]);PengramProfileHistory.applyRetentionNow();rebuild();}).show();}
    private void showStorageChoice(){String[] a={getString(R.string.PengramProfileStorageLocal),getString(R.string.PengramProfileStorageCloud),getString(R.string.PengramProfileStorageBoth)};new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileHistoryStorage)).setItems(a,(d,w)->{PengramConfig.setIntValue(PengramProfileHistory.KEY_STORAGE,w);PengramConfig.setBool(PengramProfileHistory.KEY_ENABLED,true);if(w>0)org.telegram.messenger.PengramProfileCloud.schedule(currentAccount);rebuild();}).setNegativeButton(getString(R.string.Cancel),null).show();}
    private void showScopeChoice(){String[] a={getString(R.string.PengramProfileScopeManual),getString(R.string.PengramProfileScopeInteracted),getString(R.string.PengramProfileScopeContacts),getString(R.string.PengramProfileScopeAll)};new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileHistoryScope)).setItems(a,(d,w)->{PengramConfig.setIntValue(PengramProfileHistory.KEY_SCOPE,w);rebuild();}).show();}
    private void showDisableChoice(){String[] choices={getString(R.string.PengramProfileDisableKeep),getString(R.string.PengramProfileDisableLocal),getString(R.string.PengramProfileDisableAll)};new AlertDialog.Builder(getContext()).setTitle(getString(R.string.PengramProfileHistoryDisable)).setMessage(getString(R.string.PengramProfileHistoryDisableInfo)).setItems(choices,(d,which)->{PengramConfig.setBool(PengramProfileHistory.KEY_ENABLED,false);if(which>=1)PengramProfileHistory.clearLocal();if(which==2)org.telegram.messenger.PengramProfileCloud.deleteCloud(currentAccount);rebuild();}).setNegativeButton(getString(R.string.Cancel),null).show();}
}
