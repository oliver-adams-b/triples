package com.antsapps.triples;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import androidx.appcompat.app.ActionBar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ViewAnimator;

import com.antsapps.triples.backend.Card;
import com.antsapps.triples.backend.Game;
import com.antsapps.triples.backend.Game.GameState;
import com.antsapps.triples.backend.Game.OnUpdateGameStateListener;
import com.antsapps.triples.cardsview.CardsView;
import com.google.common.collect.ImmutableList;
import com.google.firebase.analytics.FirebaseAnalytics;

public abstract class BaseGameActivity extends BaseTriplesActivity
    implements OnUpdateGameStateListener, Game.OnUpdateCardsInPlayListener {

  public static final int VIEW_CARDS = 0;
  public static final int VIEW_PAUSED = 1;
  public static final int VIEW_COMPLETED = 2;

  private static final long AUTO_RESTART_TIMEOUT_MS = -1; // unused, read from prefs

  private FirebaseAnalytics mFirebaseAnalytics;

  private ViewAnimator mViewAnimator;
  private CardsView mCardsView;
  private View mButtonBar;
  private GameState mGameState;

  private boolean shouldSubmitScoreOnSignIn = false;

  private static final String TAG = "BaseGameActivity";
  private final Handler mAutoRestartHandler = new Handler();
  private boolean mAutoRestartPending = false;
  private final Runnable mAutoRestartRunnable = new Runnable() {
    @Override
    public void run() {
      Log.d(TAG, "autoRestartRunnable fired, gameState=" + getGame().getGameState());
      if (getGame().getGameState() == GameState.ACTIVE) {
        mAutoRestartPending = true;
        deleteCurrentGame();
        newGame(null);
      }
    }
  };

  /** Called when the activity is first created. */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.game);

    init(savedInstanceState);

    getGame().addOnUpdateGameStateListener(this);
    GameState originalGameState = getGame().getGameState();

    mCardsView = (CardsView) findViewById(R.id.cards_view);
    mCardsView.setOnValidTripleSelectedListener(getGame());
    mCardsView.setEnabled(originalGameState != GameState.COMPLETED);
    getGame().setGameRenderer(mCardsView);

    mViewAnimator = findViewById(R.id.view_switcher);
    mButtonBar = findViewById(R.id.button_bar);

    getGame().addOnUpdateCardsInPlayListener(this);

    ActionBar actionBar = getSupportActionBar();
    actionBar.setDisplayHomeAsUpEnabled(true);

    getGame().begin();

    if (originalGameState == GameState.STARTING) {
      mCardsView.shouldSlideIn();
    }

    mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
  }

  protected abstract Game getGame();

  /**
   * This must initialize the game (so that getGame() doesn't return null) and set the content view
   * with a layout that contains a StatusBar (R.id.status_bar) and a CardsView (R.id.cards_view).
   *
   * @param savedInstanceState
   */
  protected abstract void init(Bundle savedInstanceState);

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);
    menu.findItem(R.id.pause).setVisible(mGameState == GameState.ACTIVE);
    menu.findItem(R.id.play).setVisible(mGameState == GameState.PAUSED);
    return true;
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.game, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle item selection
    int itemId = item.getItemId();
    if (itemId == R.id.hint) {
      getGame().addHint();
      return true;
    } else if (itemId == R.id.pause) {
      getGame().pause();
      return true;
    } else if (itemId == R.id.play) {
      getGame().resume();
      return true;
    } else if (itemId == R.id.help) {
      Intent helpIntent = new Intent(getBaseContext(), HelpActivity.class);
      startActivity(helpIntent);
      return true;
    } else if (itemId == R.id.settings) {
      Intent settingsIntent = new Intent(getBaseContext(), SettingsActivity.class);
      startActivity(settingsIntent);
      return true;
    } else if (itemId == android.R.id.home) {// app icon in action bar clicked; go up one level
      Intent intent = new Intent(this, getParentClass());
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
      startActivity(intent);
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  protected abstract Class<? extends BaseGameListActivity> getParentClass();

  @Override
  protected void onResume() {
    super.onResume();
    resetAutoRestartTimer();
    getGame().resumeFromLifecycle();

    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    if (sharedPref.getBoolean(getString(R.string.pref_screen_lock), true)) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    if (sharedPref.getBoolean(getString(R.string.pref_orientation_lock), false)) {
      String orientation = sharedPref.getString(getString(R.string.pref_orientation), "portrait");
      if (orientation.equals("portrait")) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
      } else if (orientation.equals("landscape")) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      }
    } else {
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
    }
    updateViewSwitcher();
  }

  @Override
  protected void onPause() {
    super.onPause();
    mAutoRestartHandler.removeCallbacks(mAutoRestartRunnable);
    Log.d(TAG, "onPause: mAutoRestartPending=" + mAutoRestartPending);
    if (!mAutoRestartPending) {
      saveGame();
    }
    getGame().pauseFromLifecycle();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    updateViewSwitcher();
  }

  protected abstract void saveGame();

  protected abstract void deleteCurrentGame();

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putLong(Game.ID_TAG, getGame().getId());
  }

  @Override
  protected void onDestroy() {
    mAutoRestartHandler.removeCallbacks(mAutoRestartRunnable);
    getGame().removeOnUpdateCardsInPlayListener(this);
    getGame().setGameRenderer(null);
    getGame().removeOnUpdateGameStateListener(this);

    super.onDestroy();
  }

  @Override
  public void onUpdateGameState(GameState state) {
    mGameState = state;

    updateViewSwitcher();

    if (mGameState == GameState.COMPLETED) {
      mCardsView.setAlpha(0.5f);
      mAutoRestartHandler.removeCallbacks(mAutoRestartRunnable);
    }

    invalidateOptionsMenu();
  }

  private int mLastNumTriplesFound = -1;

  @Override
  public void onUpdateCardsInPlay(ImmutableList<Card> newCards, ImmutableList<Card> oldCards,
      int numRemaining, int numTriplesFound) {
    Log.d(TAG, "onUpdateCardsInPlay: numTriplesFound=" + numTriplesFound + " last=" + mLastNumTriplesFound);
    if (numTriplesFound > mLastNumTriplesFound && mLastNumTriplesFound >= 0) {
      Log.d(TAG, "triple found! resetting auto-restart timer");
      resetAutoRestartTimer();
    }
    mLastNumTriplesFound = numTriplesFound;
  }

  @Override
  public void onCardHinted(Card card) {}

  private void resetAutoRestartTimer() {
    mAutoRestartHandler.removeCallbacks(mAutoRestartRunnable);
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    boolean enabled = prefs.getBoolean(getString(R.string.pref_auto_restart_enabled), false);
    if (enabled) {
      int seconds = Math.max(10, prefs.getInt(getString(R.string.pref_auto_restart_timeout), 60));
      Log.d(TAG, "resetAutoRestartTimer: scheduling restart in " + seconds + "s");
      mAutoRestartHandler.postDelayed(mAutoRestartRunnable, seconds * 1000L);
    }
  }

  public void shuffleCards(View view) {
    getGame().shuffleCardsInPlay();
  }

  @Override
  public void gameFinished() {
    Log.i("BaseGameActivity", "game finished");
    updateViewSwitcher();
    logGameFinished();
    if (isSignedIn()) {
      submitScore();
    } else {
      shouldSubmitScoreOnSignIn = true;
    }
  }

  private void logGameFinished() {
    Bundle bundle = new Bundle();
    bundle.putString(AnalyticsConstants.Param.GAME_TYPE, getGame().getGameTypeForAnalytics());
    mFirebaseAnalytics.logEvent(AnalyticsConstants.Event.FINISH_GAME, bundle);
  }

  protected abstract void submitScore();

  private void updateViewSwitcher() {
    int childToDisplay = VIEW_CARDS;
    if (mGameState == GameState.PAUSED || !getGame().getActivityLifecycleActive()) {
      childToDisplay = VIEW_PAUSED;
    } else if (mGameState == GameState.COMPLETED) {
      childToDisplay = VIEW_COMPLETED;
    } else {
      childToDisplay = VIEW_CARDS;
    }
    if (mViewAnimator.getDisplayedChild() != childToDisplay) {
      mViewAnimator.setDisplayedChild(childToDisplay);
    }
    if (mButtonBar != null) {
      mButtonBar.setVisibility(childToDisplay == VIEW_CARDS ? View.VISIBLE : View.GONE);
    }
  }

  @Override
  public void onSignInFailed() {}

  @Override
  public void onSignInSucceeded() {
    if (mGameState == GameState.COMPLETED && shouldSubmitScoreOnSignIn) {
      submitScore();
    }
    shouldSubmitScoreOnSignIn = false;
  }

  @Override
  public void onSignOut() {}

  public void newGame(View view) {
    Intent newGameIntent = createNewGame();
    logNewGame();
    startActivity(newGameIntent);
  }

  protected abstract Intent createNewGame();

  private void logNewGame() {
    Bundle bundle = new Bundle();
    bundle.putString(AnalyticsConstants.Param.GAME_TYPE, getGame().getGameTypeForAnalytics());
    mFirebaseAnalytics.logEvent(AnalyticsConstants.Event.NEW_GAME, bundle);
  }
}
