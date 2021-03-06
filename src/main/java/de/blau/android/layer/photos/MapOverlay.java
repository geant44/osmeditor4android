package de.blau.android.layer.photos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import de.blau.android.Map;
import de.blau.android.R;
import de.blau.android.layer.ClickableInterface;
import de.blau.android.layer.DisableInterface;
import de.blau.android.layer.MapViewLayer;
import de.blau.android.osm.ViewBox;
import de.blau.android.photos.Photo;
import de.blau.android.photos.PhotoIndex;
import de.blau.android.prefs.Preferences;
import de.blau.android.resources.DataStyle;
import de.blau.android.util.ACRAHelper;
import de.blau.android.util.GeoMath;
import de.blau.android.util.Snack;
import de.blau.android.views.IMapView;

/**
 * implement a geo-referenced photo overlay, code stolen from the OSB implementation
 * 
 * @author simon
 *
 */
public class MapOverlay extends MapViewLayer implements DisableInterface, ClickableInterface {

    private static final String DEBUG_TAG = "PhotoOverlay";

    /** viewbox needs to be less wide than this for displaying bugs, just to avoid querying the whole world for bugs */
    private static final int TOLERANCE_MIN_VIEWBOX_WIDTH = 40000 * 32;

    /** Map this is an overlay of. */
    private final Map map;

    /** Photos visible on the overlay. */
    private Collection<Photo> photos;

    /** have we already run a scan? */
    private boolean indexed = false;

    /** set while we are actually creating index */
    private boolean indexing = false;

    /** default icon */
    private final Drawable icon;

    /** selected icon */
    private final Drawable icon_selected;

    /** icon dimensions */
    private int h2;
    private int w2;

    /**
     * Index disk/in-memory of photos
     */
    private PhotoIndex pi = null;

    /**
     * Pref for this layer enabled
     */
    private boolean enabled = false;

    /** last selected photo, may not be stil displayed */
    private Photo selected = null;

    /**
     * Request to update the bugs for the current view. Ensure cur is set before invoking.
     */
    private final AsyncTask<Void, Integer, Void> indexPhotos = new AsyncTask<Void, Integer, Void>() {

        @Override
        protected Void doInBackground(Void... params) {
            if (!indexing) {
                indexing = true;
                publishProgress(0);
                pi.createOrUpdateIndex();
                pi.fill(null);
                publishProgress(1);
                indexing = false;
                indexed = true;
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            if (progress[0] == 0) {
                Snack.barInfoShort(map, R.string.toast_photo_indexing_started);
            }
            if (progress[0] == 1) {
                Snack.barInfoShort(map, R.string.toast_photo_indexing_finished);
            }
        }

        @Override
        protected void onPostExecute(Void params) {
            if (indexed) {
                map.invalidate();
            }
        }
    };

    public MapOverlay(final Map map) {
        Context context = map.getContext();
        this.map = map;
        photos = new ArrayList<>();
        icon = ContextCompat.getDrawable(context, R.drawable.camera_red);
        icon_selected = ContextCompat.getDrawable(context, R.drawable.camera_green);
        // note this assumes the icons are the same size
        w2 = icon.getIntrinsicWidth() / 2;
        h2 = icon.getIntrinsicHeight() / 2;

        pi = new PhotoIndex(context);
    }

    @Override
    public boolean isReadyToDraw() {
        enabled = map.getPrefs().isPhotoLayerEnabled();
        return enabled;
    }

    @Override
    protected void onDraw(Canvas c, IMapView osmv) {
        if (isVisible && enabled) {
            ViewBox bb = osmv.getViewBox();

            if ((bb.getWidth() > TOLERANCE_MIN_VIEWBOX_WIDTH) || (bb.getHeight() > TOLERANCE_MIN_VIEWBOX_WIDTH)) {
                return;
            }

            if (!indexed && !indexing) {
                indexPhotos.execute();
                return;
            }

            // draw all the photos
            int w = map.getWidth();
            int h = map.getHeight();
            photos = pi.getPhotos(bb);

            for (Photo p : photos) {
                Drawable i;
                if (p == selected) {
                    i = icon_selected;
                } else {
                    i = icon;
                }
                int x = (int) GeoMath.lonE7ToX(w, bb, p.getLon());
                int y = (int) GeoMath.latE7ToY(h, w, bb, p.getLat());
                i.setBounds(new Rect(x - w2, y - h2, x + w2, y + h2));
                if (p.hasDirection()) {
                    c.rotate(p.getDirection(), x, y);
                    i.draw(c);
                    c.rotate(-p.getDirection(), x, y);
                } else {
                    i.draw(c);
                }
            }
        }
    }

    @Override
    protected void onDrawFinished(Canvas c, IMapView osmv) {
        // do nothing
    }

    @Override
    public List<Photo> getClicked(final float x, final float y, final ViewBox viewBox) {
        List<Photo> result = new ArrayList<>();
        Log.d("photos.MapOverlay", "getClickedPhotos");
        if (map.getPrefs().isPhotoLayerEnabled()) {
            final float tolerance = DataStyle.getCurrent().getNodeToleranceValue();
            for (Photo p : photos) {
                int lat = p.getLat();
                int lon = p.getLon();
                float differenceX = Math.abs(GeoMath.lonE7ToX(map.getWidth(), viewBox, lon) - x);
                float differenceY = Math.abs(GeoMath.latE7ToY(map.getHeight(), map.getWidth(), viewBox, lat) - y);
                if ((differenceX <= tolerance) && (differenceY <= tolerance)) {
                    if (Math.hypot(differenceX, differenceY) <= tolerance) {
                        Uri photoUri = p.getRefUri(map.getContext());
                        if (photoUri != null) { // only return valid entries
                            result.add(p);
                        }
                    }
                }
            }
        }
        Log.d("photos.MapOverlay", "getClickedPhotos found " + result.size());
        return result;
    }

    public void setSelected(Photo photo) {
        selected = photo;
    }

    @Override
    public String getName() {
        return map.getContext().getString(R.string.layer_photos);
    }

    @Override
    public void invalidate() {
        map.invalidate();
    }

    @Override
    public void disable(Context ctx) {
        Preferences prefs = new Preferences(ctx);
        prefs.setPhotoLayerEnabled(false);
    }

    @Override
    public void onSelected(FragmentActivity activity, Object object) {
        if (!(object instanceof Photo)) {
            Log.e(DEBUG_TAG, "Wrong object for " + getName() + " " + object.getClass().getName());
            return;
        }
        Photo photo = (Photo) object;
        try {
            Intent myIntent = new Intent(Intent.ACTION_VIEW);
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags = flags | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                flags = flags | Intent.FLAG_ACTIVITY_CLEAR_TASK;
            }
            myIntent.setFlags(flags);
            Context context = map.getContext();
            Uri photoUri = photo.getRefUri(context);
            if (photoUri != null) {
                // black magic only works this way
                myIntent.setDataAndType(photoUri, "image/jpeg");
                context.startActivity(myIntent);
                setSelected(photo);
                // TODO may need a map.invalidate() here
            } else {
                Snack.toastTopError(context, context.getResources().getString(R.string.toast_error_accessing_photo, photo.getRef()));
            }
        } catch (Exception ex) {
            Log.d(DEBUG_TAG, "viewPhoto exception starting intent: " + ex);
            ACRAHelper.nocrashReport(ex, "viewPhoto exception starting intent");
        }
    }

    @Override
    public String getDescription(Object object) {
        if (!(object instanceof Photo)) {
            Log.e(DEBUG_TAG, "Wrong object for " + getName() + " " + object.getClass().getName());
            return "?";
        }
        Photo photo = (Photo) object;
        Uri photoUri = photo.getRefUri(map.getContext());
        if (photoUri != null) {
            return photoUri.getLastPathSegment();
        }
        return "?";
    }
}
