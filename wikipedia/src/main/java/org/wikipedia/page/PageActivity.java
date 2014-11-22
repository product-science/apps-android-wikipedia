package org.wikipedia.page;

import org.wikipedia.NavDrawerFragment;
import org.wikipedia.PageTitle;
import org.wikipedia.R;
import org.wikipedia.Site;
import org.wikipedia.ThemedActionBarActivity;
import org.wikipedia.Utils;
import org.wikipedia.ViewAnimations;
import org.wikipedia.WikipediaApp;
import org.wikipedia.events.ChangeTextSizeEvent;
import org.wikipedia.events.ThemeChangeEvent;
import org.wikipedia.events.WikipediaZeroInterstitialEvent;
import org.wikipedia.events.WikipediaZeroStateChangeEvent;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.interlanguage.LangLinksActivity;
import org.wikipedia.onboarding.OnboardingActivity;
import org.wikipedia.recurring.RecurringTasksExecutor;
import org.wikipedia.search.SearchArticlesFragment;
import org.wikipedia.settings.PrefKeys;
import org.wikipedia.staticdata.MainPageNameData;
import org.wikipedia.theme.ThemeChooserDialog;
import org.wikipedia.zero.ZeroMessage;

import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.Toolbar;
import android.text.Html;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;

public class PageActivity extends ThemedActionBarActivity {
    public static final String ACTION_PAGE_FOR_TITLE = "org.wikipedia.page_for_title";
    public static final String EXTRA_PAGETITLE = "org.wikipedia.pagetitle";
    public static final String EXTRA_HISTORYENTRY  = "org.wikipedia.history.historyentry";
    private static final String ZERO_ON_NOTICE_PRESENTED = "org.wikipedia.zero.zeroOnNoticePresented";

    private static final String KEY_LAST_FRAGMENT = "lastFragment";
    private static final String KEY_LAST_FRAGMENT_ARGS = "lastFragmentArgs";

    public static final int ACTIVITY_REQUEST_LANGLINKS = 0;
    public static final int ACTIVITY_REQUEST_EDIT_SECTION = 1;

    private Bus bus;
    private WikipediaApp app;

    private View fragmentContainerView;
    private DrawerLayout drawerLayout;
    private NavDrawerFragment fragmentNavdrawer;
    private SearchArticlesFragment searchFragment;

    public static final int PROGRESS_BAR_MAX_VALUE = 10000;
    private ProgressBar progressBar;

    private View toolbarContainer;
    public View getToolbarView() {
        return toolbarContainer;
    }

    private ActionBarDrawerToggle mDrawerToggle;
    public ActionBarDrawerToggle getDrawerToggle() {
        return mDrawerToggle;
    }

    /**
     * Get the Fragment that is currently at the top of the Activity's backstack.
     * This activity's fragment container will hold multiple fragments stacked onto
     * each other using FragmentManager, and this function will return the current
     * topmost Fragment. It's up to the caller to cast the result to a more specific
     * fragment class, and perform actions on it.
     * @return Fragment at the top of the backstack.
     */
    public Fragment getTopFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.content_fragment_container);
    }

    /**
     * Get the PageViewFragment that is currently at the top of the Activity's backstack.
     * If the current topmost fragment is not a PageViewFragment, return null.
     * @return The PageViewFragment at the top of the backstack, or null if the current
     * top fragment is not a PageViewFragment.
     */
    public PageViewFragmentInternal getCurPageFragment() {
        Fragment f = getTopFragment();
        if (f instanceof PageViewFragment) {
            return ((PageViewFragment)f).getFragment();
        } else {
            return null;
        }
    }

    private boolean pausedStateOfZero;
    private ZeroMessage pausedMessageOfZero;

    private AlertDialog.Builder alert;

    private ThemeChooserDialog themeChooser;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (WikipediaApp) getApplicationContext();

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setContentView(R.layout.activity_main);

        final Toolbar toolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);

        toolbarContainer = findViewById(R.id.main_toolbar_container);

        bus = app.getBus();
        bus.register(this);

        drawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        fragmentNavdrawer = (NavDrawerFragment) getSupportFragmentManager().findFragmentById(R.id.navdrawer);
        searchFragment = (SearchArticlesFragment) getSupportFragmentManager().findFragmentById(R.id.search_fragment);

        fragmentContainerView = findViewById(R.id.content_fragment_container);
        progressBar = (ProgressBar)findViewById(R.id.main_progressbar);
        progressBar.setMax(PROGRESS_BAR_MAX_VALUE);
        updateProgressBar(false, true, 0);

        mDrawerToggle = new ActionBarDrawerToggle(
                this,                  /* host Activity */
                drawerLayout,          /* DrawerLayout object */
                R.string.app_name,     /* "open drawer" description */
                R.string.app_name      /* "close drawer" description */
        ) {

            public void onDrawerClosed(View view) {
                super.onDrawerClosed(view);
                // if we want to change the title upon closing:
                //getSupportActionBar().setTitle("");
            }

            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                // if we want to change the title upon opening:
                //getSupportActionBar().setTitle("");
            }

            private boolean oncePerSlideLock = false;

            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                super.onDrawerSlide(drawerView, slideOffset);
                if (!oncePerSlideLock) {
                    // Hide the keyboard when the drawer is opened
                    Utils.hideSoftKeyboard(PageActivity.this);
                    //also make sure ToC is hidden
                    if (getCurPageFragment() != null) {
                        getCurPageFragment().toggleToC(PageViewFragmentInternal.TOC_ACTION_HIDE);
                    }
                    //and make sure to update dynamic items and highlights
                    fragmentNavdrawer.setupDynamicItems();
                    oncePerSlideLock = true;
                }
                // and make sure the Toolbar is showing
                showToolbar();
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                super.onDrawerStateChanged(newState);
                if (newState == DrawerLayout.STATE_IDLE) {
                    oncePerSlideLock = false;
                }
            }
        };

        // Set the drawer toggle as the DrawerListener
        drawerLayout.setDrawerListener(mDrawerToggle);
        getSupportActionBar().setTitle("");

        // TODO: remove this when we drop support for API 10
        boolean themeChanged = false;
        try {
            themeChanged = getIntent().hasExtra("changeTheme");
            // remove this extra, in case the activity is relaunched automatically
            // e.g. during screen rotation.
            getIntent().removeExtra("changeTheme");
        } catch (BadParcelableException e) {
            // this may be thrown when an app such as Facebook puts its own private Parcelable
            // into the intent. Since we don't know about the class of the Parcelable, we can't
            // unparcel it properly, so the hasExtra method may fail.
            Log.w("PageActivity", "Received an unknown parcelable in intent:", e);
        }

        if (savedInstanceState != null) {
            pausedStateOfZero = savedInstanceState.getBoolean("pausedStateOfZero");
            pausedMessageOfZero = savedInstanceState.getParcelable("pausedMessageOfZero");
            if (savedInstanceState.containsKey("themeChooserShowing")) {
                if (savedInstanceState.getBoolean("themeChooserShowing")) {
                    showThemeChooser();
                }
            }
            if (savedInstanceState.getBoolean("isSearching")) {
                searchFragment.openSearch();
            }
        } else if (themeChanged) {
            // we've changed themes!
            pausedStateOfZero = getIntent().getExtras().getBoolean("pausedStateOfZero");
            pausedMessageOfZero = getIntent().getExtras().getParcelable("pausedMessageOfZero");
            if (getIntent().getExtras().containsKey("themeChooserShowing")) {
                if (getIntent().getExtras().getBoolean("themeChooserShowing")) {
                    showThemeChooser();
                }
            }
            if (getIntent().getExtras().getBoolean("isSearching")) {
                searchFragment.openSearch();
            }
        }

        // If we're coming back from a Theme change, we'll need to "restore" our state based on
        // what's given in our Intent (since there's no way to relaunch the Activity in a way that
        // forces it to save its own instance state)...
        // TODO: remove this when we drop support for API 10
        if (themeChanged) {
            String className = getIntent().getExtras().getString(KEY_LAST_FRAGMENT);
            try {
                // instantiate the last fragment that was on top of the backstack before the Activity
                // was closed:
                Fragment f = (Fragment) Class.forName(className).getConstructor().newInstance();
                // if we have arguments for the fragment, even better:
                if (getIntent().getExtras().containsKey(KEY_LAST_FRAGMENT_ARGS)) {
                    f.setArguments(getIntent().getExtras().getBundle(KEY_LAST_FRAGMENT_ARGS));
                }
                // ...and put it on top:
                pushFragment(f);
            } catch (Exception e) {
                //multiple various exceptions may be thrown in the above few lines, so just catch all.
                Log.e("PageActivity", "Error while instantiating fragment.", e);
                //don't let the user see a blank screen, so just request the main page...
                displayMainPage();
            }
        } else if (savedInstanceState == null) {
            // if there's no savedInstanceState, and we're not coming back from a Theme change,
            // then we must have been launched with an Intent, so... handle it!
            handleIntent(getIntent());
        }

        // Conditionally execute all recurring tasks
        new RecurringTasksExecutor(this).run();

        if (showOnboarding()) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Sync the toggle state after onRestoreInstanceState has occurred.
        mDrawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (searchFragment != null && !isSearching()) {
            getMenuInflater().inflate(R.menu.menu_main, menu);
            app.adjustDrawableToTheme(menu.findItem(R.id.menu_search).getIcon());
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Pass the event to ActionBarDrawerToggle, if it returns
        // true, then it has handled the app icon touch event
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.menu_search:
                searchFragment.openSearch();
                return true;
            default:
                break;
        }
        // Handle other action bar items...
        return super.onOptionsItemSelected(item);
    }

    public void showToolbar() {
        ViewAnimations.ensureTranslationY(toolbarContainer, 0);
    }

    /**
     * @return true if
     * (1:) the app was launched from the launcher (and not another app, like the browser) AND
     * (2:) none of the onboarding screen buttons had been clicked AND
     * (3:) the user is not logged in
     */
    private boolean showOnboarding() {
        return (getIntent() == null || Intent.ACTION_MAIN.equals(getIntent().getAction()))
                && !PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PrefKeys.getOnboard(), false)
                && !app.getUserInfoStorage().isLoggedIn();
    }

    private void handleIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Site site = new Site(intent.getData().getAuthority());
            PageTitle title = site.titleForUri(intent.getData());
            HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_EXTERNAL_LINK);
            displayNewPage(title, historyEntry);
        } else if (ACTION_PAGE_FOR_TITLE.equals(intent.getAction())) {
            PageTitle title = intent.getParcelableExtra(EXTRA_PAGETITLE);
            HistoryEntry historyEntry = intent.getParcelableExtra(EXTRA_HISTORYENTRY);
            displayNewPage(title, historyEntry);
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            PageTitle title = new PageTitle(query, app.getPrimarySite());
            HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_SEARCH);
            displayNewPage(title, historyEntry);
        } else {
            // Unrecognized, let us load the main page!
            // FIXME: Design something better for this?
            displayMainPage();
        }
    }

    /**
     * Update the state of the main progress bar that is shown inside the ActionBar of the activity.
     * @param visible Whether the progress bar is visible.
     * @param indeterminate Whether the progress bar is indeterminate.
     * @param value Value of the progress bar (may be between 0 and 10000). Ignored if the
     *              progress bar is indeterminate.
     */
    public void updateProgressBar(boolean visible, boolean indeterminate, int value) {
        progressBar.setIndeterminate(indeterminate);
        if (!indeterminate) {
            progressBar.setProgress(value);
        }
        progressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Returns whether we're currently in a "searching" state (i.e. the search fragment is shown).
     * @return True if currently searching, false otherwise.
     */
    public boolean isSearching() {
        if (searchFragment == null) {
            return false;
        }
        return searchFragment.isSearchActive();
    }

    /**
     * Add a new fragment to the top of the activity's backstack.
     * @param f New fragment to place on top.
     */
    public void pushFragment(Fragment f) {
        drawerLayout.closeDrawer(Gravity.START);
        // if the new fragment is the same class as the current topmost fragment,
        // then just keep the previous fragment there. (unless it's a PageViewFragment)
        // e.g. if the user selected History, and there's already a History fragment on top,
        // then there's no need to load a new History fragment.
        if (getTopFragment() != null
                && (getTopFragment().getClass() == f.getClass())
                && !(f instanceof PageViewFragment)) {
            return;
        }
        int totalFragments = getSupportFragmentManager().getBackStackEntryCount();
        FragmentTransaction trans = getSupportFragmentManager().beginTransaction();

        // do an animation on the new fragment, but only if there was a previous one before it.
        if (getTopFragment() != null) {
            trans.setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right);
        }

        trans.replace(R.id.content_fragment_container, f, "fragment_" + Integer.toString(totalFragments));
        trans.addToBackStack(null);
        trans.commit();

        // and make sure the ActionBar is visible
        showToolbar();
        //also make sure the progress bar is not showing
        updateProgressBar(false, true, 0);
    }

    /**
     * Remove the fragment that is currently at the top of the backstack, and go back to
     * the previous fragment.
     */
    public void popFragment() {
        getSupportFragmentManager().popBackStack();
        // make sure the ActionBar is showing, since we could be currently scrolled down far enough
        // within a Page fragment that the ActionBar is hidden, and if the previous fragment was
        // a different type of fragment (e.g. History), the ActionBar would remain hidden.
        showToolbar();
        //also make sure the progress bar is not showing
        updateProgressBar(false, true, 0);
    }

    public void displayNewPage(final PageTitle title, final HistoryEntry entry) {
        if (drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
        }
        if (title.isSpecial()) {
            Utils.visitInExternalBrowser(this, Uri.parse(title.getMobileUri()));
            return;
        }

        //is the new title the same as what's already being displayed?
        if (getTopFragment() instanceof PageViewFragment) {
            if (((PageViewFragment)getTopFragment()).getFragment().getTitle().equals(title)) {
                return;
            }
            getCurPageFragment().closeFindInPage();
        }

        pushFragment(PageViewFragment.newInstance(title, entry));

        app.getSessionFunnel().pageViewed(entry);
    }

    public void displayMainPage() {
        PageTitle title = new PageTitle(MainPageNameData.valueFor(app.getPrimaryLanguage()), app.getPrimarySite());
        HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_MAIN_PAGE);
        displayNewPage(title, historyEntry);
    }

    public void showThemeChooser() {
        if (themeChooser == null) {
            themeChooser = new ThemeChooserDialog(this);
        }
        themeChooser.show();
    }

    @Subscribe
    public void onChangeTextSize(ChangeTextSizeEvent event) {
        if (getCurPageFragment() != null && getCurPageFragment().getWebView() != null) {
            getCurPageFragment().updateFontSize();
        }
    }

    @Subscribe
    public void onChangeTheme(ThemeChangeEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {

            // this is all that's necessary!
            // ALL of the other code relating to changing themes is only for API 10 support!
            this.recreate();

        } else {
            // TODO: remove this when we drop support for API 10
            // sigh.
            Bundle state = new Bundle();
            Intent intent = new Intent(this, PageActivity.class);
            // In order to change our theme, we need to relaunch the activity.
            // There doesn't seem to be a way to relaunch an activity in a way that forces it to save its
            // instance state (and all of its fragments' instance state)... so we need to explicitly save
            // the state that we need, and pass it into the Intent.
            // We'll simply save the last Fragment that was on top of the backstack, as well as its arguments.
            Fragment curFragment = getSupportFragmentManager().findFragmentById(R.id.content_fragment_container);
            state.putString(KEY_LAST_FRAGMENT, curFragment.getClass().getName());
            // if the fragment had arguments, save them too:
            if (curFragment.getArguments() != null) {
                state.putBundle(KEY_LAST_FRAGMENT_ARGS, curFragment.getArguments());
            }

            saveState(state);
            state.putBoolean("changeTheme", true);
            finish();
            intent.putExtras(state);
            startActivity(intent);
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(Gravity.START)) {
            drawerLayout.closeDrawer(Gravity.START);
            return;
        }
        if (searchFragment.onBackPressed()) {
            return;
        }
        if (!(getCurPageFragment() != null && getCurPageFragment().handleBackPressed())) {

            app.getSessionFunnel().backPressed();

            if (getSupportFragmentManager().getBackStackEntryCount() > 1) {
                popFragment();
            } else {
                finish();
            }
        }
    }

    @Subscribe
    public void onWikipediaZeroStateChangeEvent(WikipediaZeroStateChangeEvent event) {
        boolean latestWikipediaZeroDisposition = app.getWikipediaZeroHandler().isZeroEnabled();
        ZeroMessage latestCarrierMessage = app.getWikipediaZeroHandler().getCarrierMessage();

        if (pausedStateOfZero && !latestWikipediaZeroDisposition) {
            String title = getString(R.string.zero_charged_verbiage);
            String verbiage = getString(R.string.zero_charged_verbiage_extended);
            makeWikipediaZeroCrouton(getResources().getColor(R.color.holo_red_dark),
                    getResources().getColor(android.R.color.white),
                    title);
            fragmentNavdrawer.setupDynamicItems();
            showDialogAboutZero(null, title, verbiage);
        } else if ((!pausedStateOfZero || !pausedMessageOfZero.equals(latestCarrierMessage)) && latestWikipediaZeroDisposition) {
            String title = latestCarrierMessage.getMsg();
            int fg = latestCarrierMessage.getFg();
            int bg = latestCarrierMessage.getBg();
            String verbiage = getString(R.string.zero_learn_more);
            makeWikipediaZeroCrouton(bg, fg, title);
            fragmentNavdrawer.setupDynamicItems();
            showDialogAboutZero(ZERO_ON_NOTICE_PRESENTED, title, verbiage);
        }
        pausedStateOfZero = latestWikipediaZeroDisposition;
        pausedMessageOfZero = latestCarrierMessage;
    }

    private void makeWikipediaZeroCrouton(int bgcolor, int fgcolor, String verbiage) {
        final int zeroTextSize = 20;
        final float croutonHeight = 192.0f;
        Style style = new Style.Builder()
                .setBackgroundColorValue(bgcolor)
                .setGravity(Gravity.CENTER)
                // .setTextAppearance-driven font size is not being honored, so we'll do it manually
                // Text size in library is in sp
                .setTextSize(zeroTextSize)
                .setTextColorValue(fgcolor)
                // Height size in library is in px
                .setHeight((int) Math.floor(croutonHeight * WikipediaApp.getInstance().getScreenDensity()))
                .build();

        Crouton.makeText(this, verbiage, style, R.id.content_fragment_container).show();
    }

    private void showDialogAboutZero(final String prefsKey, String title, String message) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        if (prefsKey == null || !prefs.getBoolean(prefsKey, false)) {
            if (prefsKey != null) {
                prefs.edit().putBoolean(prefsKey, true).apply();
            }

            alert = new AlertDialog.Builder(this);
            alert.setMessage(Html.fromHtml("<b>" + title + "</b><br/><br/>" + message));
            if (prefsKey != null) {
                alert.setPositiveButton(getString(R.string.zero_learn_more_learn_more), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        Utils.visitInExternalBrowser(PageActivity.this, Uri.parse(getString(R.string.zero_webpage_url)));
                    }
                });
            }
            alert.setNegativeButton(getString(R.string.zero_learn_more_dismiss), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.dismiss();
                }
            });
            AlertDialog ad = alert.create();
            ad.show();
        }
    }

    @Subscribe
    public void onWikipediaZeroInterstitialEvent(final WikipediaZeroInterstitialEvent event) {
        alert = new AlertDialog.Builder(this);
        alert.setTitle(getString(R.string.zero_interstitial_title));
        alert.setMessage(getString(R.string.zero_interstitial_leave_app));
        alert.setPositiveButton(getString(R.string.zero_interstitial_continue), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                Utils.visitInExternalBrowser(PageActivity.this, event.getUri());
            }
        });
        alert.setNegativeButton(getString(R.string.zero_interstitial_cancel), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
            }
        });
        AlertDialog ad = alert.create();
        ad.show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (bus == null) {
            bus = app.getBus();
            bus.register(this);
            Log.d("Wikipedia", "Registering bus");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean latestWikipediaZeroDisposition = app.getWikipediaZeroHandler().isZeroEnabled();
        if (pausedStateOfZero && !latestWikipediaZeroDisposition) {
            bus.post(new WikipediaZeroStateChangeEvent());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pausedStateOfZero = app.getWikipediaZeroHandler().isZeroEnabled();
        pausedMessageOfZero = app.getWikipediaZeroHandler().getCarrierMessage();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveState(outState);
    }

    private void saveState(Bundle outState) {
        outState.putBoolean("pausedStateOfZero", pausedStateOfZero);
        outState.putParcelable("pausedMessageOfZero", pausedMessageOfZero);
        if (themeChooser != null) {
            outState.putBoolean("themeChooserShowing", themeChooser.isShowing());
        }
        outState.putBoolean("isSearching", isSearching());
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (bus == null) {
            bus = app.getBus();
            bus.register(this);
            Log.d("Wikipedia", "Registering bus");
        }
        if ((requestCode == ACTIVITY_REQUEST_LANGLINKS && resultCode == LangLinksActivity.ACTIVITY_RESULT_LANGLINK_SELECT)) {
            fragmentContainerView.post(new Runnable() {
                @Override
                public void run() {
                    handleIntent(data);
                }
            });
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onStop() {
        if (themeChooser != null && themeChooser.isShowing()) {
            themeChooser.dismiss();
        }

        app.getSessionFunnel().persistSession();

        super.onStop();
        bus.unregister(this);
        bus = null;
        Log.d("Wikipedia", "Deregistering bus");
    }
}
