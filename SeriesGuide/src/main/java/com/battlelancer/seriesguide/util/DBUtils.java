package com.battlelancer.seriesguide.util;

import android.annotation.SuppressLint;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.widget.Toast;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.SgApp;
import com.battlelancer.seriesguide.adapters.CalendarAdapter;
import com.battlelancer.seriesguide.dataliberation.DataLiberationTools;
import com.battlelancer.seriesguide.dataliberation.model.Show;
import com.battlelancer.seriesguide.enums.EpisodeFlags;
import com.battlelancer.seriesguide.enums.SeasonTags;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Seasons;
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Shows;
import com.battlelancer.seriesguide.settings.CalendarSettings;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.ui.CalendarFragment.CalendarType;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.greenrobot.eventbus.EventBus;
import timber.log.Timber;

import static com.battlelancer.seriesguide.provider.SeriesGuideDatabase.Qualified;

public class DBUtils {

    /**
     * Use for unknown release time/no next episode so they will get sorted last in show list (value
     * is {@link Long#MAX_VALUE}). See {@link Shows#NEXTAIRDATEMS}.
     */
    public static final String UNKNOWN_NEXT_RELEASE_DATE = String.valueOf(Long.MAX_VALUE);

    /**
     * Used if the number of remaining episodes to watch for a show is not (yet) known.
     *
     * @see Shows#UNWATCHED_COUNT
     */
    public static final int UNKNOWN_UNWATCHED_COUNT = -1;
    public static final int UNKNOWN_COLLECTED_COUNT = -1;

    private static final int ACTIVITY_DAY_LIMIT = 30;
    private static final int SMALL_BATCH_SIZE = 50;

    private static final String[] PROJECTION_COUNT = new String[] {
            BaseColumns._COUNT
    };

    public static class DatabaseErrorEvent {

        private final String message;
        private final boolean isCorrupted;

        DatabaseErrorEvent(String message, boolean isCorrupted) {
            this.message = message;
            this.isCorrupted = isCorrupted;
        }

        public void handle(Context context) {
            StringBuilder errorText = new StringBuilder(context.getString(R.string.database_error));
            if (isCorrupted) {
                errorText.append(" ").append(context.getString(R.string.reinstall_info));
            }
            errorText.append(" (").append(message).append(")");
            Toast.makeText(context, errorText, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Post an event to simply show a toast with the error message.
     */
    public static void postDatabaseError(SQLiteException e) {
        EventBus.getDefault()
                .post(new DatabaseErrorEvent(e.getMessage(),
                        e instanceof SQLiteDatabaseCorruptException));
    }

    /**
     * Maps a {@link java.lang.Boolean} object to an int value to store in the database.
     */
    public static int convertBooleanToInt(Boolean value) {
        if (value == null) {
            return 0;
        }
        return value ? 1 : 0;
    }

    /**
     * Maps an integer value stored in the database to a boolean.
     */
    public static boolean restoreBooleanFromInt(int value) {
        return value == 1;
    }

    /**
     * Triggers the rebuilding of the episode search table.
     */
    public static void rebuildFtsTable(Context context) {
        Timber.d("Query to renew FTS table");
        context.getContentResolver()
                .query(SeriesGuideContract.EpisodeSearch.CONTENT_URI_RENEWFTSTABLE, null, null,
                        null, null);
    }

    interface UnwatchedQuery {
        String AIRED_SELECTION = Episodes.WATCHED + "=0 AND " + Episodes.FIRSTAIREDMS
                + " !=-1 AND " + Episodes.FIRSTAIREDMS + "<=?";

        String AIRED_SELECTION_NO_SPECIALS = AIRED_SELECTION
                + " AND " + Episodes.SELECTION_NO_SPECIALS;

        String FUTURE_SELECTION = Episodes.WATCHED + "=0 AND " + Episodes.FIRSTAIREDMS
                + ">?";

        String NOAIRDATE_SELECTION = Episodes.WATCHED + "=0 AND "
                + Episodes.FIRSTAIREDMS + "=-1";

        String SKIPPED_SELECTION = Episodes.WATCHED + "=" + EpisodeFlags.SKIPPED;
    }

    /**
     * Looks up the episodes of a given season and stores the count of all, unwatched and skipped
     * ones in the seasons watch counters.
     */
    public static void updateUnwatchedCount(Context context, int seasonTvdbId) {
        final ContentResolver resolver = context.getContentResolver();
        final Uri uri = Episodes.buildEpisodesOfSeasonUri(seasonTvdbId);

        // all a seasons episodes
        final int totalCount = getCountOf(resolver, uri, null, null, -1);
        if (totalCount == -1) {
            return;
        }

        // unwatched, aired episodes
        String[] customCurrentTimeArgs = { String.valueOf(TimeTools.getCurrentTime(context)) };
        final int count = getCountOf(resolver, uri, UnwatchedQuery.AIRED_SELECTION,
                customCurrentTimeArgs, -1);
        if (count == -1) {
            return;
        }

        // unwatched, aired in the future episodes
        final int unairedCount = getCountOf(resolver, uri, UnwatchedQuery.FUTURE_SELECTION,
                customCurrentTimeArgs, -1);
        if (unairedCount == -1) {
            return;
        }

        // unwatched, no airdate
        final int noAirDateCount = getCountOf(resolver, uri,
                UnwatchedQuery.NOAIRDATE_SELECTION, null, -1);
        if (noAirDateCount == -1) {
            return;
        }

        // any skipped episodes
        int skippedCount = getCountOf(resolver, uri, UnwatchedQuery.SKIPPED_SELECTION, null,
                -1);
        if (skippedCount == -1) {
            return;
        }

        final ContentValues update = new ContentValues();
        update.put(Seasons.WATCHCOUNT, count);
        update.put(Seasons.UNAIREDCOUNT, unairedCount);
        update.put(Seasons.NOAIRDATECOUNT, noAirDateCount);
        update.put(Seasons.TAGS, skippedCount > 0 ? SeasonTags.SKIPPED : SeasonTags.NONE);
        update.put(Seasons.TOTALCOUNT, totalCount);
        resolver.update(Seasons.buildSeasonUri(seasonTvdbId), update, null, null);
    }

    /**
     * Returns how many episodes of a show are left to watch (only aired and not watched, exclusive
     * episodes with no air date and without specials).
     *
     * @return {@link #UNKNOWN_UNWATCHED_COUNT} if the number is unknown or failed to be determined.
     */
    public static int getUnwatchedEpisodesOfShow(Context context, String showId) {
        if (context == null) {
            return UNKNOWN_UNWATCHED_COUNT;
        }

        // unwatched, aired episodes
        return getCountOf(context.getContentResolver(),
                Episodes.buildEpisodesOfShowUri(showId),
                UnwatchedQuery.AIRED_SELECTION_NO_SPECIALS,
                new String[] {
                        String.valueOf(TimeTools.getCurrentTime(context))
                }, UNKNOWN_UNWATCHED_COUNT);
    }

    /**
     * Returns how many episodes of a show are left to collect. Only considers regular, released
     * episodes (no specials, must have a release date in the past).
     */
    public static int getUncollectedEpisodesOfShow(Context context, String showId) {
        if (context == null) {
            return UNKNOWN_COLLECTED_COUNT;
        }

        // not collected, no special, previously released episodes
        return getCountOf(context.getContentResolver(), Episodes.buildEpisodesOfShowUri(showId),
                Episodes.SELECTION_NOT_COLLECTED
                        + " AND " + Episodes.SELECTION_NO_SPECIALS
                        + " AND " + Episodes.SELECTION_HAS_RELEASE_DATE
                        + " AND " + Episodes.SELECTION_RELEASED_BEFORE_X,
                new String[] {
                        String.valueOf(TimeTools.getCurrentTime(context))
                },
                UNKNOWN_COLLECTED_COUNT);
    }

    public static int getCountOf(@NonNull ContentResolver resolver, @NonNull Uri uri,
            @Nullable String selection, @Nullable String[] selectionArgs, int defaultValue) {
        Cursor cursor = resolver.query(uri, PROJECTION_COUNT, selection, selectionArgs, null);
        if (cursor == null) {
            return defaultValue;
        }
        if (!cursor.moveToFirst()) {
            cursor.close();
            return defaultValue;
        }
        int count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    /**
     * Returns episodes that air today or within the next {@link #ACTIVITY_DAY_LIMIT} days. Excludes
     * shows that are hidden.
     *
     * <p>Filters by watched episodes or favorite shows if enabled.
     *
     * @return Cursor using the projection of {@link CalendarAdapter.Query}.
     */
    @Nullable
    public static Cursor upcomingEpisodesQuery(Context context, boolean isOnlyUnwatched) {
        boolean isOnlyCollected = CalendarSettings.isOnlyCollected(context);
        boolean isOnlyFavorites = CalendarSettings.isOnlyFavorites(context);
        return activityQuery(context, CalendarType.UPCOMING, isOnlyCollected, isOnlyFavorites,
                isOnlyUnwatched, false);
    }

    /**
     * @return Cursor with projection {@link CalendarAdapter.Query}.
     * @see #buildActivityQuery(Context, String, boolean, boolean, boolean, boolean)
     */
    @Nullable
    public static Cursor activityQuery(Context context, String type, boolean isOnlyCollected,
            boolean isOnlyFavorites, boolean isOnlyUnwatched, boolean isInfinite) {
        String[][] args = buildActivityQuery(context, type, isOnlyCollected, isOnlyFavorites,
                isOnlyUnwatched, isInfinite);
        return context.getContentResolver().query(Episodes.CONTENT_URI_WITHSHOW,
                CalendarAdapter.Query.PROJECTION, args[0][0], args[1], args[2][0]);
    }

    /**
     * Returns an array of size 3. The built query is stored in {@code [0][0]}, the built selection
     * args in {@code [1]} and the sort order in {@code [2][0]}.
     *
     * @param type A {@link CalendarType}, defaults to UPCOMING.
     * @param isInfinite If false, limits the release time range of returned episodes to {@link
     * #ACTIVITY_DAY_LIMIT} days from today.
     */
    public static String[][] buildActivityQuery(Context context, String type,
            boolean isOnlyCollected, boolean isOnlyFavorites, boolean isOnlyUnwatched,
            boolean isInfinite) {
        // go an hour back in time, so episodes move to recent one hour late
        long recentThreshold = TimeTools.getCurrentTime(context) - DateUtils.HOUR_IN_MILLIS;

        StringBuilder query;
        String[] selectionArgs;
        String sortOrder;
        long timeThreshold;

        if (CalendarType.RECENT.equals(type)) {
            query = new StringBuilder(CalendarAdapter.Query.QUERY_RECENT);
            sortOrder = CalendarAdapter.Query.SORTING_RECENT;
            if (isInfinite) {
                // to the past!
                timeThreshold = Long.MIN_VALUE;
            } else {
                // last x days
                timeThreshold = System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS
                        * ACTIVITY_DAY_LIMIT;
            }
        } else {
            query = new StringBuilder(CalendarAdapter.Query.QUERY_UPCOMING);
            sortOrder = CalendarAdapter.Query.SORTING_UPCOMING;
            if (isInfinite) {
                // to the future!
                timeThreshold = Long.MAX_VALUE;
            } else {
                // coming x days
                timeThreshold = System.currentTimeMillis() + DateUtils.DAY_IN_MILLIS
                        * ACTIVITY_DAY_LIMIT;
            }
        }

        selectionArgs = new String[] {
                String.valueOf(recentThreshold), String.valueOf(timeThreshold)
        };

        // append only favorites selection if necessary
        if (isOnlyFavorites) {
            query.append(" AND ").append(Shows.SELECTION_FAVORITES);
        }

        // append no specials selection if necessary
        boolean isNoSpecials = DisplaySettings.isHidingSpecials(context);
        if (isNoSpecials) {
            query.append(" AND ").append(Episodes.SELECTION_NO_SPECIALS);
        }

        // append unwatched selection if necessary
        if (isOnlyUnwatched) {
            query.append(" AND ").append(Episodes.SELECTION_UNWATCHED);
        }

        // only show collected episodes
        if (isOnlyCollected) {
            query.append(" AND ").append(Episodes.SELECTION_COLLECTED);
        }

        // build result array
        String[][] results = new String[3][];
        results[0] = new String[] {
                query.toString()
        };
        results[1] = selectionArgs;
        results[2] = new String[] {
                sortOrder
        };
        return results;
    }

    /**
     * Marks the next episode (if there is one) of the given show as watched. Submits it to trakt if
     * possible.
     */
    public static void markNextEpisode(Context context, int showId, int episodeId) {
        if (episodeId > 0) {
            Cursor episode = context.getContentResolver().query(
                    Episodes.buildEpisodeUri(String.valueOf(episodeId)), new String[] {
                            Episodes.SEASON, Episodes.NUMBER
                    }, null, null, null
            );
            if (episode != null) {
                if (episode.moveToFirst()) {
                    EpisodeTools.episodeWatched(context, showId, episodeId, episode.getInt(0),
                            episode.getInt(1), EpisodeFlags.WATCHED);
                }
                episode.close();
            }
        }
    }

    private static final String[] SHOW_PROJECTION = new String[] {
            Shows._ID,
            Shows.POSTER,
            Shows.TITLE
    };

    /**
     * Returns a {@link Show} object with only TVDB id, title and poster populated. Might return
     * {@code null} if there is no show with that TVDb id.
     */
    @Nullable
    public static Show getShow(Context context, int showTvdbId) {
        Cursor details = context.getContentResolver().query(Shows.buildShowUri(showTvdbId),
                SHOW_PROJECTION, null,
                null, null);

        Show show = null;
        if (details != null) {
            if (details.moveToFirst()) {
                show = new Show();
                show.tvdb_id = details.getInt(0);
                show.poster = details.getString(1);
                show.title = details.getString(2);
            }
            details.close();
        }

        return show;
    }

    /**
     * Queries the show table for the given TVDb id and returns whether there are entries, e.g. the
     * show is already in the database.
     */
    public static boolean isShowExists(Context context, int showTvdbId) {
        Cursor testsearch = context.getContentResolver().query(Shows.buildShowUri(showTvdbId),
                new String[] {
                        Shows._ID
                }, null, null, null
        );
        if (testsearch == null) {
            return false;
        }
        boolean isShowExists = testsearch.getCount() != 0;
        testsearch.close();
        return isShowExists;
    }

    /**
     * Builds a {@link ContentProviderOperation} for inserting or updating a show (depending on
     * {@code isNew}).
     *
     * <p> If the show is new, sets some default values and the (TheTVDB) id.
     */
    public static ContentProviderOperation buildShowOp(Context context, Show show, boolean isNew) {
        ContentValues values = new ContentValues();

        // values for new and existing shows
        // if in any case the title is empty, show a place holder
        values.put(Shows.TITLE,
                TextUtils.isEmpty(show.title) ? context.getString(R.string.no_translation_title)
                        : show.title);
        values.put(Shows.TITLE_NOARTICLE, trimLeadingArticle(show.title));
        values.put(Shows.OVERVIEW, show.overview);
        values.put(Shows.POSTER, show.poster);
        values.put(Shows.CONTENTRATING, show.content_rating);
        values.put(Shows.STATUS, DataLiberationTools.encodeShowStatus(show.status));
        values.put(Shows.RUNTIME, show.runtime);
        values.put(Shows.RATING_GLOBAL, show.rating);
        values.put(Shows.NETWORK, show.network);
        values.put(Shows.GENRES, show.genres);
        values.put(Shows.FIRST_RELEASE, show.first_aired);
        values.put(Shows.RELEASE_TIME, show.release_time);
        values.put(Shows.RELEASE_WEEKDAY, show.release_weekday);
        values.put(Shows.RELEASE_TIMEZONE, show.release_timezone);
        values.put(Shows.RELEASE_COUNTRY, show.country);
        values.put(Shows.IMDBID, show.imdb_id);
        values.put(Shows.TRAKT_ID, show.trakt_id);
        values.put(Shows.LASTUPDATED, System.currentTimeMillis());
        values.put(Shows.LASTEDIT, show.last_edited);

        if (isNew) {
            // set TheTVDB id
            values.put(Shows._ID, show.tvdb_id);
            values.put(Shows.LANGUAGE, show.language);
            // set user values
            values.put(Shows.FAVORITE, show.favorite);
            values.put(Shows.NOTIFY, show.notify == null ? true : show.notify);
            values.put(Shows.HIDDEN, show.hidden);
            // set default values
            values.put(Shows.RATING_VOTES, 0);
            values.put(Shows.RATING_USER, 0);
            values.put(Shows.HEXAGON_MERGE_COMPLETE, 1);
            values.put(Shows.NEXTEPISODE, "");
            values.put(Shows.NEXTTEXT, "");
            values.put(Shows.NEXTAIRDATEMS, UNKNOWN_NEXT_RELEASE_DATE);
            values.put(Shows.NEXTAIRDATETEXT, "");
            values.put(Shows.LASTWATCHEDID, 0);
            return ContentProviderOperation.newInsert(Shows.CONTENT_URI).withValues(values).build();
        } else {
            return ContentProviderOperation
                    .newUpdate(Shows.buildShowUri(String.valueOf(show.tvdb_id)))
                    .withValues(values).build();
        }
    }

    /**
     * Returns the episode IDs and their last edit time for a given show as a efficiently searchable
     * HashMap.
     *
     * @return HashMap containing the shows existing episodes
     */
    public static HashMap<Integer, Long> getEpisodeMapForShow(Context context, int showTvdbId) {
        Cursor episodes = context.getContentResolver().query(
                Episodes.buildEpisodesOfShowUri(showTvdbId), new String[] {
                        Episodes._ID, Episodes.LAST_EDITED
                }, null, null, null
        );
        @SuppressLint("UseSparseArrays") HashMap<Integer, Long> episodeMap = new HashMap<>();
        if (episodes != null) {
            while (episodes.moveToNext()) {
                episodeMap.put(episodes.getInt(0), episodes.getLong(1));
            }
            episodes.close();
        }
        return episodeMap;
    }

    /**
     * Returns the season IDs for a given show as a efficiently searchable HashMap.
     *
     * @return HashMap containing the shows existing seasons
     */
    public static HashSet<Integer> getSeasonIdsOfShow(Context context, int showTvdbId) {
        Cursor seasons = context.getContentResolver().query(
                Seasons.buildSeasonsOfShowUri(showTvdbId),
                new String[] {
                        Seasons._ID
                }, null, null, null
        );
        HashSet<Integer> seasonIds = new HashSet<>();
        if (seasons != null) {
            while (seasons.moveToNext()) {
                seasonIds.add(seasons.getInt(0));
            }
            seasons.close();
        }
        return seasonIds;
    }

    /**
     * Creates an update {@link ContentProviderOperation} for the given episode values.
     */
    public static ContentProviderOperation buildEpisodeUpdateOp(ContentValues values) {
        final String episodeId = values.getAsString(Episodes._ID);
        return ContentProviderOperation
                .newUpdate(Episodes.buildEpisodeUri(episodeId))
                .withValues(values).build();
    }

    /**
     * Creates a {@link ContentProviderOperation} for insert if isNew, or update instead for with
     * the given season values.
     */
    public static ContentProviderOperation buildSeasonOp(ContentValues values, boolean isNew) {
        ContentProviderOperation op;
        final String seasonId = values.getAsString(Seasons.REF_SEASON_ID);
        final ContentValues seasonValues = new ContentValues();
        seasonValues.put(Seasons.COMBINED, values.getAsString(Episodes.SEASON));

        if (isNew) {
            seasonValues.put(Seasons._ID, seasonId);
            seasonValues.put(Shows.REF_SHOW_ID, values.getAsString(Shows.REF_SHOW_ID));
            op = ContentProviderOperation.newInsert(Seasons.CONTENT_URI).withValues(seasonValues)
                    .build();
        } else {
            op = ContentProviderOperation.newUpdate(Seasons.buildSeasonUri(seasonId))
                    .withValues(seasonValues).build();
        }
        return op;
    }

    private interface LastWatchedEpisodeQuery {
        String[] PROJECTION = new String[] {
                Qualified.SHOWS_ID,
                Shows.LASTWATCHEDID,
                Episodes.SEASON,
                Episodes.NUMBER,
                Episodes.FIRSTAIREDMS
        };

        int SHOW_TVDB_ID = 0;
        int LAST_EPISODE_TVDB_ID = 1;
        int LAST_EPISODE_SEASON = 2;
        int LAST_EPISODE_NUMBER = 3;
        int LAST_EPISODE_FIRST_RELEASE_MS = 4;
    }

    private interface NextEpisodesQuery {
        String[] PROJECTION = new String[] {
                Episodes._ID,
                Episodes.SEASON,
                Episodes.NUMBER,
                Episodes.FIRSTAIREDMS,
                Episodes.TITLE
        };

        /**
         * Unwatched, airing later or has a different number or season if airing the same time.
         */
        String SELECT_NEXT = Episodes.WATCHED + "=0 AND ("
                + "(" + Episodes.FIRSTAIREDMS + "=? AND "
                + "(" + Episodes.NUMBER + "!=? OR " + Episodes.SEASON + "!=?)) "
                + "OR " + Episodes.FIRSTAIREDMS + ">?)";

        String SELECT_WITHAIRDATE = " AND " + Episodes.FIRSTAIREDMS + "!=-1";

        String SELECT_ONLYFUTURE = " AND " + Episodes.FIRSTAIREDMS + ">=?";

        /**
         * Air time, then lowest season, or if identical lowest episode number.
         */
        String SORTORDER = Episodes.FIRSTAIREDMS + " ASC," + Episodes.SEASON + " ASC,"
                + Episodes.NUMBER + " ASC";

        int ID = 0;
        int SEASON = 1;
        int NUMBER = 2;
        int FIRST_RELEASE_MS = 3;
        int TITLE = 4;
    }

    /**
     * Update next episode field and unwatched episode count for the given show. If no show id is
     * passed, will update next episodes for all shows.
     *
     * @return If only one show was passed, the TVDb id of the new next episode. Otherwise -1.
     */
    public static long updateLatestEpisode(Context context, Integer showTvdbIdToUpdate) {
        // get a list of shows and their last watched episodes
        Cursor shows;
        try {
            shows = context.getContentResolver().query(Shows.CONTENT_URI_WITH_LAST_EPISODE,
                    LastWatchedEpisodeQuery.PROJECTION,
                    showTvdbIdToUpdate != null ?
                            Qualified.SHOWS_ID + "=" + showTvdbIdToUpdate : null,
                    null, null
            );
        } catch (SQLiteException e) {
            shows = null;
            Timber.e(e, "updateLatestEpisode: show query failed.");
            postDatabaseError(e);
        }
        if (shows == null) {
            // abort completely on query failure
            Timber.e("Failed to update next episode values");
            return -1;
        }
        final List<String[]> showsLastEpisodes = new ArrayList<>();
        while (shows.moveToNext()) {
            showsLastEpisodes.add(
                    new String[] {
                            shows.getString(LastWatchedEpisodeQuery.SHOW_TVDB_ID), // 0
                            shows.getString(LastWatchedEpisodeQuery.LAST_EPISODE_TVDB_ID), // 1
                            shows.getString(LastWatchedEpisodeQuery.LAST_EPISODE_SEASON), // 2
                            shows.getString(LastWatchedEpisodeQuery.LAST_EPISODE_NUMBER), // 3
                            shows.getString(LastWatchedEpisodeQuery.LAST_EPISODE_FIRST_RELEASE_MS)
                            // 4
                    }
            );
        }
        shows.close();

        // pre-build next episode selection
        final boolean isNoReleasedEpisodes = DisplaySettings.isNoReleasedEpisodes(context);
        final String nextEpisodeSelection = buildNextEpisodeSelection(
                DisplaySettings.isHidingSpecials(context), isNoReleasedEpisodes);

        // build updated next episode values for each show
        int nextEpisodeTvdbId = -1;
        final ContentValues newShowValues = new ContentValues();
        final ArrayList<ContentProviderOperation> batch = new ArrayList<>();
        final String currentTime = String.valueOf(TimeTools.getCurrentTime(context));
        final boolean displayExactDate = DisplaySettings.isDisplayExactDate(context);
        DisplaySettings.preventSpoilers(context);
        for (String[] show : showsLastEpisodes) {
            // STEP 1: get last watched episode details
            final String showTvdbId = show[0];
            final String lastEpisodeTvdbId = show[1];
            String season = show[2];
            String number = show[3];
            String releaseTime = show[4];
            if (TextUtils.isEmpty(lastEpisodeTvdbId)
                    || season == null || number == null || releaseTime == null) {
                // by default: no watched episodes, include all starting with special 0
                season = "-1";
                number = "-1";
                releaseTime = String.valueOf(Long.MIN_VALUE);
            }

            // STEP 2: get episode released closest afterwards; or at the same time,
            // but with a higher number
            final String[] selectionArgs;
            if (isNoReleasedEpisodes) {
                // restrict to episodes with future release date
                selectionArgs = new String[] {
                        releaseTime, number, season, releaseTime, currentTime
                };
            } else {
                // restrict to episodes with any valid air date
                selectionArgs = new String[] {
                        releaseTime, number, season, releaseTime
                };
            }
            Cursor next;
            try {
                next = context.getContentResolver()
                        .query(Episodes.buildEpisodesOfShowUri(showTvdbId),
                                NextEpisodesQuery.PROJECTION, nextEpisodeSelection, selectionArgs,
                                NextEpisodesQuery.SORTORDER);
            } catch (SQLiteException e) {
                next = null;
                Timber.e(e, "updateLatestEpisode: next episode query failed.");
                postDatabaseError(e);
            }
            if (next == null) {
                // abort completely on query failure
                Timber.e("Failed to update next episode values");
                return -1;
            }

            // STEP 3: build updated next episode values
            if (next.moveToFirst()) {
                final String nextEpisodeString;
                int seasonNumber = next.getInt(NextEpisodesQuery.SEASON);
                int episodeNumber = next.getInt(NextEpisodesQuery.NUMBER);
                if (DisplaySettings.preventSpoilers(context)) {
                    // just the number, like '0x12'
                    nextEpisodeString = TextTools.getEpisodeNumber(context,
                            seasonNumber,
                            episodeNumber);
                } else {
                    // next episode text, like '0x12 Episode Name'
                    nextEpisodeString = TextTools.getNextEpisodeString(context,
                            seasonNumber,
                            episodeNumber,
                            next.getString(NextEpisodesQuery.TITLE));
                }

                // next release date text, e.g. "in 15 mins (Fri)"
                long releaseTimeNext = next.getLong(NextEpisodesQuery.FIRST_RELEASE_MS);
                Date actualRelease = TimeTools.applyUserOffset(context, releaseTimeNext);
                String dateTime = displayExactDate ?
                        TimeTools.formatToLocalDateShort(context, actualRelease)
                        : TimeTools.formatToLocalRelativeTime(context, actualRelease);
                final String nextReleaseDateString = context.getString(
                        R.string.release_date_and_day,
                        dateTime,
                        TimeTools.formatToLocalDay(actualRelease));

                nextEpisodeTvdbId = next.getInt(NextEpisodesQuery.ID);
                newShowValues.put(Shows.NEXTEPISODE, nextEpisodeTvdbId);
                newShowValues.put(Shows.NEXTAIRDATEMS, releaseTimeNext);
                newShowValues.put(Shows.NEXTTEXT, nextEpisodeString);
                newShowValues.put(Shows.NEXTAIRDATETEXT, nextReleaseDateString);
            } else {
                // no next episode, set empty values
                nextEpisodeTvdbId = 0;
                newShowValues.put(Shows.NEXTEPISODE, "");
                newShowValues.put(Shows.NEXTAIRDATEMS, UNKNOWN_NEXT_RELEASE_DATE);
                newShowValues.put(Shows.NEXTTEXT, "");
                newShowValues.put(Shows.NEXTAIRDATETEXT, "");
            }
            next.close();

            // STEP 4: get remaining episodes count
            int unwatchedEpisodesCount = getUnwatchedEpisodesOfShow(context, showTvdbId);
            newShowValues.put(Shows.UNWATCHED_COUNT, unwatchedEpisodesCount);

            // update the show with the new next episode values
            batch.add(ContentProviderOperation.newUpdate(Shows.buildShowUri(showTvdbId))
                    .withValues(newShowValues)
                    .build());
            newShowValues.clear();
        }

        try {
            DBUtils.applyInSmallBatches(context, batch);
        } catch (OperationApplicationException e) {
            Timber.e(e, "Failed to update next episode values");
            return -1;
        }

        return nextEpisodeTvdbId;
    }

    private static String buildNextEpisodeSelection(boolean isHidingSpecials,
            boolean isNoReleasedEpisodes) {
        StringBuilder nextEpisodeSelectionBuilder = new StringBuilder(
                NextEpisodesQuery.SELECT_NEXT);
        if (isHidingSpecials) {
            // do not take specials into account
            nextEpisodeSelectionBuilder.append(" AND ").append(Episodes.SELECTION_NO_SPECIALS);
        }
        if (isNoReleasedEpisodes) {
            // restrict to episodes with future release date
            nextEpisodeSelectionBuilder.append(NextEpisodesQuery.SELECT_ONLYFUTURE);
        } else {
            // restrict to episodes with any valid air date
            nextEpisodeSelectionBuilder.append(NextEpisodesQuery.SELECT_WITHAIRDATE);
        }
        return nextEpisodeSelectionBuilder.toString();
    }

    /**
     * Applies a large {@link ContentProviderOperation} batch in smaller batches as not to overload
     * the transaction cache.
     */
    public static void applyInSmallBatches(Context context,
            ArrayList<ContentProviderOperation> batch) throws OperationApplicationException {
        // split into smaller batches to not overload transaction cache
        // see http://developer.android.com/reference/android/os/TransactionTooLargeException.html

        ArrayList<ContentProviderOperation> smallBatch = new ArrayList<>();

        while (!batch.isEmpty()) {
            if (batch.size() <= SMALL_BATCH_SIZE) {
                // small enough already? apply right away
                applyBatch(context, batch);
                return;
            }

            // take up to 50 elements out of batch
            for (int count = 0; count < SMALL_BATCH_SIZE; count++) {
                if (batch.isEmpty()) {
                    break;
                }
                smallBatch.add(batch.remove(0));
            }

            // apply small batch
            applyBatch(context, smallBatch);

            // prepare for next small batch
            smallBatch.clear();
        }
    }

    private static void applyBatch(Context context, ArrayList<ContentProviderOperation> batch)
            throws OperationApplicationException {
        try {
            context.getContentResolver()
                    .applyBatch(SgApp.CONTENT_AUTHORITY, batch);
        } catch (RemoteException e) {
            // not using a remote provider, so this should never happen. crash if it does.
            throw new RuntimeException("Problem applying batch operation", e);
        } catch (SQLiteException e) {
            Timber.e(e, "applyBatch: failed, database error.");
            postDatabaseError(e);
        }
    }

    /**
     * Removes a leading article from the given string (including the first whitespace that
     * follows). <p> <em>Currently only supports English articles (the, a and an).</em>
     */
    public static String trimLeadingArticle(String title) {
        if (TextUtils.isEmpty(title)) {
            return title;
        }

        if (title.length() > 4 &&
                (title.startsWith("The ") || title.startsWith("the "))) {
            return title.substring(4);
        }
        if (title.length() > 2 &&
                (title.startsWith("A ") || title.startsWith("a "))) {
            return title.substring(2);
        }
        if (title.length() > 3 &&
                (title.startsWith("An ") || title.startsWith("an "))) {
            return title.substring(3);
        }

        return title;
    }
}
