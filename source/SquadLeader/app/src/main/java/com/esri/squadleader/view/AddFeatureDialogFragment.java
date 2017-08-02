/*******************************************************************************
 * Copyright 2017 Esri
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 ******************************************************************************/
package com.esri.squadleader.view;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.esri.android.map.FeatureLayer;
import com.esri.android.map.GraphicsLayer;
import com.esri.android.map.Layer;
import com.esri.android.map.event.OnSingleTapListener;
import com.esri.android.map.popup.Popup;
import com.esri.core.geodatabase.GeodatabaseFeatureTable;
import com.esri.core.geodatabase.GeopackageFeatureTable;
import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.MultiPath;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.Polygon;
import com.esri.core.geometry.Polyline;
import com.esri.core.map.Feature;
import com.esri.core.map.FeatureEditResult;
import com.esri.core.map.Graphic;
import com.esri.core.symbol.SimpleFillSymbol;
import com.esri.core.symbol.SimpleLineSymbol;
import com.esri.core.symbol.SimpleMarkerSymbol;
import com.esri.core.table.FeatureTable;
import com.esri.core.table.TableException;
import com.esri.core.tasks.query.QueryParameters;
import com.esri.squadleader.R;
import com.esri.squadleader.controller.EditingState;
import com.esri.squadleader.controller.GeometryEditController;
import com.esri.squadleader.controller.MapController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/**
 * A dialog for adding a feature to a feature table. An Activity that creates this dialog must implement
 * AddFeatureDialogFragment.AddFeatureListener in order to work properly.<br/>
 * <br/>
 * A lot of this code comes from the GeometryEditorActivity editor class in the ArcGIS Runtime SDK
 * for Android samples.
 */
public class AddFeatureDialogFragment extends DialogFragment {

    /**
     * A listener for this class to interact with the calling class.
     */
    public interface AddFeatureListener {

        /**
         * @return the MapController containing the layers to which a feature can be added.
         */
        MapController getMapController();

        /**
         * Called when a feature is added.
         *
         * @param popup a Popup for the added feature.
         */
        void featureAdded(Popup popup);

        /**
         * @return an OnSingleTapListener to be used when this fragment is no longer in use.
         */
        OnSingleTapListener getDefaultOnSingleTapListener();

    }

    public static final String ARG_FEATURE_LAYERS = "feature layers";

    private static final String TAG = AddFeatureDialogFragment.class.getSimpleName();
    private static final SimpleMarkerSymbol redMarkerSymbol = new SimpleMarkerSymbol(Color.RED, 20, SimpleMarkerSymbol.STYLE.CIRCLE);
    private static final SimpleMarkerSymbol blackMarkerSymbol = new SimpleMarkerSymbol(Color.BLACK, 20, SimpleMarkerSymbol.STYLE.CIRCLE);
    private static final SimpleMarkerSymbol greenMarkerSymbol = new SimpleMarkerSymbol(Color.GREEN, 15, SimpleMarkerSymbol.STYLE.CIRCLE);
    private static final String TAG_DIALOG_FRAGMENTS = "dialog";

    private final GeometryEditController geometryEditController = new GeometryEditController();
    private ArrayList<Point> midPoints = new ArrayList<>();
    private final OnSingleTapListener editingListener = new OnSingleTapListener() {
        @Override
        public void onSingleTap(final float x, final float y) {
            Point point = mapController.toMapPointObject(Math.round(x), Math.round(y));
            if (geometryEditController.getEditMode() == GeometryEditController.EditMode.POINT) {
                geometryEditController.getCurrentEditingState().clearPoints();
            }
            if (geometryEditController.getCurrentEditingState().isMidPointSelected() || geometryEditController.getCurrentEditingState().isVertexSelected()) {
                movePoint(point);
            } else {
                // If tap coincides with a mid-point, select that mid-point
                int idx1 = getSelectedIndex(x, y, midPoints, mapController);
                if (idx1 != -1) {
                    geometryEditController.getCurrentEditingState().setMidPointSelected(true);
                    geometryEditController.getCurrentEditingState().setInsertingIndex(idx1);
                } else {
                    // If tap coincides with a vertex, select that vertex
                    int idx2 = getSelectedIndex(x, y, geometryEditController.getCurrentEditingState().getPoints(), mapController);
                    if (idx2 != -1) {
                        geometryEditController.getCurrentEditingState().setVertexSelected(true);
                        geometryEditController.getCurrentEditingState().setInsertingIndex(idx2);
                    } else {
                        // No matching point above, add new vertex at tap point
                        geometryEditController.getCurrentEditingState().addPoint(point);
                        geometryEditController.addEditingState(new EditingState(geometryEditController.getCurrentEditingState()));
                    }
                }
            }
            refresh();
        }
    };

    private Activity activity = null;
    private AddFeatureListener addFeatureListener = null;
    private MapController mapController = null;
    private Menu editingMenu = null;
    private GraphicsLayer graphicsLayerEditing = null;

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        activity = getActivity();
        if (activity instanceof AddFeatureListener) {
            addFeatureListener = (AddFeatureListener) activity;
        } else {
            Log.w(TAG, getString(R.string.no_add_feature_listener, activity.getClass().getName()));
        }
        LayoutInflater inflater = activity.getLayoutInflater();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        final View inflatedView = inflater.inflate(R.layout.add_feature, null);

        builder.setView(inflatedView);
        builder.setTitle(getString(R.string.add_feature));

        final ArrayList<String> layerNames = new ArrayList<String>();
        final ArrayList<FeatureLayer> featureLayers = new ArrayList<FeatureLayer>();
        if (null != addFeatureListener) {
            mapController = addFeatureListener.getMapController();
            if (null != mapController) {
                List<Layer> layers = mapController.getNonBasemapLayers();
                for (Layer layer : layers) {
                    if (layer instanceof FeatureLayer) {
                        FeatureLayer featureLayer = (FeatureLayer) layer;
                        if (featureLayer.getFeatureTable().isEditable()) {
                            layerNames.add(featureLayer.getName());
                            featureLayers.add(featureLayer);
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "Activity must implement this dialog class's AddFeatureListener interface, but " + activity.getClass().getName() + " does not");
        }

        ArrayAdapter<FeatureLayer> adapter = new ArrayAdapter<FeatureLayer>(activity, R.layout.layer_list_item, featureLayers) {
            @NonNull
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView dropDownView = (TextView) getDropDownView(position, convertView, parent);
                dropDownView.setText(getItem(position).getName());
                return dropDownView;
            }
        };
        ListView listView_layersToEdit = (ListView) inflatedView.findViewById(R.id.listView_layersToEdit);
        listView_layersToEdit.setAdapter(adapter);
        listView_layersToEdit.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                activity.startActionMode(new ActionMode.Callback() {
                    private final FeatureLayer layerToEdit = featureLayers.get(position);

                    @Override
                    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                        actionMode.getMenuInflater().inflate(R.menu.add_feature_context_menu, menu);
                        actionMode.setTitle(layerNames.get(position));
                        editingMenu = menu;

                        switch (layerToEdit.getGeometryType()) {
                            case MULTIPOINT:
                            case POINT:
                                geometryEditController.setEditMode(GeometryEditController.EditMode.POINT);
                                break;
                            case LINE:
                            case POLYLINE:
                                geometryEditController.setEditMode(GeometryEditController.EditMode.POLYLINE);
                                break;
                            case ENVELOPE:
                            case POLYGON:
                                geometryEditController.setEditMode(GeometryEditController.EditMode.POLYGON);
                                break;
                            default:
                                geometryEditController.setEditMode(GeometryEditController.EditMode.NONE);
                                discard();
                                dismiss();
                        }
                        updateActionBar();

                        mapController.setShowMagnifierOnLongPress(true);

                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                        editingMenu = menu;
                        return false;
                    }

                    @Override
                    public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                        boolean returnValue = false;
                        switch (menuItem.getItemId()) {
                            case R.id.save:
                                try {
                                    actionSave(layerToEdit);
                                } catch (TableException e) {
                                    Log.e(TAG, "Couldn't save edits", e);
                                }
                                actionMode.finish();
                                returnValue = true;
                                break;

                            case R.id.delete_point:
                                actionDeletePoint();
                                returnValue = true;
                                break;

                            case R.id.undo:
                                geometryEditController.undo();
                                refresh();
                                returnValue = true;
                                break;
                        }
                        updateActionBar();
                        return returnValue;
                    }

                    @Override
                    public void onDestroyActionMode(ActionMode actionMode) {
                        discard();
                    }
                });
                dismiss();
            }
        });

        return builder.create();
    }

    private void discard() {
        geometryEditController.getCurrentEditingState().clearPoints();
        geometryEditController.clearEditingStates();
        geometryEditController.setEditMode(GeometryEditController.EditMode.NONE);
        geometryEditController.getCurrentEditingState().setMidPointSelected(false);
        mapController.removeLayer(graphicsLayerEditing);
        graphicsLayerEditing = null;
        mapController.setOnSingleTapListener(addFeatureListener == null ? null : addFeatureListener.getDefaultOnSingleTapListener());
    }

    private void updateActionBar() {
        if (geometryEditController.getEditMode() == GeometryEditController.EditMode.NONE || geometryEditController.getEditMode() == GeometryEditController.EditMode.SAVING) {
            // We are not editing
            discard();
        } else {
            // We are editing
            showAction(R.id.save, isSaveValid());
            showAction(R.id.delete_point, geometryEditController.getEditMode() != GeometryEditController.EditMode.POINT && geometryEditController.getCurrentEditingState().getPointCount() > 0 && !geometryEditController.getCurrentEditingState().isMidPointSelected());
            showAction(R.id.undo, geometryEditController.getEditingStatesCount() > 0);
            mapController.setOnSingleTapListener(editingListener);
        }
    }

    private void refresh() {
        if (graphicsLayerEditing != null) {
            graphicsLayerEditing.removeAll();
        }
        drawPolylineOrPolygon();
        drawMidPoints();
        drawVertices();

        updateActionBar();
    }

    private boolean isSaveValid() {
        int minPoints;
        switch (geometryEditController.getEditMode()) {
            case POINT:
                minPoints = 1;
                break;
            case POLYGON:
                minPoints = 3;
                break;
            case POLYLINE:
                minPoints = 2;
                break;
            default:
                return false;
        }
        return geometryEditController.getCurrentEditingState().getPointCount() >= minPoints;
    }

    private void drawPolylineOrPolygon() {
        Graphic graphic;
        MultiPath multipath;

        // Create and add graphics layer if it doesn't already exist
        if (graphicsLayerEditing == null) {
            graphicsLayerEditing = new GraphicsLayer();
            mapController.addLayer(graphicsLayerEditing);
        }

        if (geometryEditController.getCurrentEditingState().getPointCount() > 1) {

            // Build a MultiPath containing the vertices
            if (geometryEditController.getEditMode() == GeometryEditController.EditMode.POLYLINE) {
                multipath = new Polyline();
            } else {
                multipath = new Polygon();
            }
            multipath.startPath(geometryEditController.getCurrentEditingState().getPoint(0));
            for (int i = 1; i < geometryEditController.getCurrentEditingState().getPointCount(); i++) {
                multipath.lineTo(geometryEditController.getCurrentEditingState().getPoint(i));
            }

            // Draw it using a line or fill symbol
            if (geometryEditController.getEditMode() == GeometryEditController.EditMode.POLYLINE) {
                graphic = new Graphic(multipath, new SimpleLineSymbol(Color.BLACK, 4));
            } else {
                SimpleFillSymbol simpleFillSymbol = new SimpleFillSymbol(Color.YELLOW);
                simpleFillSymbol.setAlpha(100);
                simpleFillSymbol.setOutline(new SimpleLineSymbol(Color.BLACK, 4));
                graphic = new Graphic(multipath, (simpleFillSymbol));
            }
            graphicsLayerEditing.addGraphic(graphic);
        }
    }

    private void drawMidPoints() {
        int index;
        Graphic graphic;

        midPoints.clear();
        if (geometryEditController.getCurrentEditingState().getPointCount() > 1) {

            // Build new list of mid-points
            for (int i = 1; i < geometryEditController.getCurrentEditingState().getPointCount(); i++) {
                Point p1 = geometryEditController.getCurrentEditingState().getPoint(i - 1);
                Point p2 = geometryEditController.getCurrentEditingState().getPoint(i);
                midPoints.add(new Point((p1.getX() + p2.getX()) / 2, (p1.getY() + p2.getY()) / 2));
            }
            if (geometryEditController.getEditMode() == GeometryEditController.EditMode.POLYGON && geometryEditController.getCurrentEditingState().getPointCount() > 2) {
                // Complete the circle
                Point p1 = geometryEditController.getCurrentEditingState().getPoint(0);
                Point p2 = geometryEditController.getCurrentEditingState().getPoint(geometryEditController.getCurrentEditingState().getPointCount() - 1);
                midPoints.add(new Point((p1.getX() + p2.getX()) / 2, (p1.getY() + p2.getY()) / 2));
            }

            // Draw the mid-points
            index = 0;
            for (Point pt : midPoints) {
                if (geometryEditController.getCurrentEditingState().isMidPointSelected() && geometryEditController.getCurrentEditingState().getInsertingIndex() == index) {
                    graphic = new Graphic(pt, redMarkerSymbol);
                } else {
                    graphic = new Graphic(pt, greenMarkerSymbol);
                }
                graphicsLayerEditing.addGraphic(graphic);
                index++;
            }
        }
    }

    private void drawVertices() {
        int index = 0;
        SimpleMarkerSymbol symbol;

        for (Point pt : geometryEditController.getCurrentEditingState().getPoints()) {
            if (geometryEditController.getCurrentEditingState().isVertexSelected() && index == geometryEditController.getCurrentEditingState().getInsertingIndex()) {
                // This vertex is currently selected so make it red
                symbol = redMarkerSymbol;
            } else if (index == geometryEditController.getCurrentEditingState().getPointCount() - 1 && !geometryEditController.getCurrentEditingState().isMidPointSelected() && !geometryEditController.getCurrentEditingState().isVertexSelected()) {
                // Last vertex and none currently selected so make it red
                symbol = redMarkerSymbol;
            } else {
                // Otherwise make it black
                symbol = blackMarkerSymbol;
            }
            Graphic graphic = new Graphic(pt, symbol);
            graphicsLayerEditing.addGraphic(graphic);
            index++;
        }
    }

    private void showAction(int resId, boolean show) {
        if (null != editingMenu) {
            MenuItem item = editingMenu.findItem(resId);
            item.setVisible(show);
        }
    }

    private void movePoint(Point point) {
        if (geometryEditController.getCurrentEditingState().isMidPointSelected()) {
            // Move mid-point to the new location and make it a vertex
            geometryEditController.getCurrentEditingState().addPoint(geometryEditController.getCurrentEditingState().getInsertingIndex() + 1, point);
        } else {
            // Must be a vertex: move it to the new location
            ArrayList<Point> temp = new ArrayList<Point>();
            for (int i = 0; i < geometryEditController.getCurrentEditingState().getPointCount(); i++) {
                if (i == geometryEditController.getCurrentEditingState().getInsertingIndex()) {
                    temp.add(point);
                } else {
                    temp.add(geometryEditController.getCurrentEditingState().getPoint(i));
                }
            }
            geometryEditController.getCurrentEditingState().clearPoints();
            geometryEditController.getCurrentEditingState().addAllPoints(temp);
        }
        // Go back to the normal drawing mode and save the new editing state
        geometryEditController.getCurrentEditingState().setMidPointSelected(false);
        geometryEditController.getCurrentEditingState().setVertexSelected(false);
        geometryEditController.addEditingState(new EditingState(geometryEditController.getCurrentEditingState()));
    }

    /**
     * Checks if a given location coincides (within a tolerance) with a point in a given array.
     *
     * @param x             Screen coordinate of location to check.
     * @param y             Screen coordinate of location to check.
     * @param points        List of points to check.
     * @param mapController the MapController for the editing app.
     * @return Index within points of matching point, or -1 if none.
     */
    private int getSelectedIndex(double x, double y, List<Point> points, MapController mapController) {
        final int TOLERANCE = 40; // Tolerance in pixels

        if (points == null || points.size() == 0) {
            return -1;
        }

        // Find closest point
        int index = -1;
        double distSQ_Small = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            Point mapPoint = points.get(i);
            double[] screenCoords = mapController.toScreenPoint(mapPoint.getX(), mapPoint.getY());
            Point p = new Point(screenCoords[0], screenCoords[1]);
            double diffx = p.getX() - x;
            double diffy = p.getY() - y;
            double distSQ = diffx * diffx + diffy * diffy;
            if (distSQ < distSQ_Small) {
                index = i;
                distSQ_Small = distSQ;
            }
        }

        // Check if it's close enough
        if (distSQ_Small < (TOLERANCE * TOLERANCE)) {
            return index;
        }
        return -1;
    }

    private void actionDeletePoint() {
        geometryEditController.getCurrentEditingState().deletePoint();
        geometryEditController.addEditingState(new EditingState(geometryEditController.getCurrentEditingState()));
        refresh();
    }

    private void actionSave(FeatureLayer layerToEdit) throws TableException {
        final FeatureTable featureTable = layerToEdit.getFeatureTable();

        Geometry geom = geometryEditController.getCurrentEditingState().getGeometry(geometryEditController.getEditMode());
        if (null != geom) {
            Feature newFeature = null;
            if (featureTable instanceof GeopackageFeatureTable) {
                newFeature = ((GeopackageFeatureTable) featureTable).createNewFeature(null, geom);
            } else if (featureTable instanceof GeodatabaseFeatureTable) {
                newFeature = ((GeodatabaseFeatureTable) featureTable).createNewFeature(null, geom);
            }

            Long featureId = null;
            if (null != newFeature) {
                try {
                    featureId = featureTable.addFeature(newFeature);
                } catch (Throwable t) {
                    Log.e(TAG, "Could not add feature", t);
                    Toast.makeText(activity, "Could not add feature: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            completeSaveAction(null, featureId, layerToEdit);
        }
    }

    private void completeSaveAction(final FeatureEditResult[][] results, final Long featureId, final FeatureLayer featureLayer) {
        if (null != activity) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    boolean success = true;
                    if (results != null) {
                        if (results[0][0].isSuccess()) {
                            String msg = getString(R.string.saved);
                            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                        } else {
                            success = false;
                            EditFailedDialogFragment frag = new EditFailedDialogFragment();
                            frag.setMessage(results[0][0].getError().getDescription());
                            frag.show(getFragmentManager(), TAG_DIALOG_FRAGMENTS);
                        }
                    }
                    activity.setProgressBarIndeterminateVisibility(false);
                    exitEditMode();

                    if (success) {
                        QueryParameters queryParameters = new QueryParameters();
                        queryParameters.setObjectIds(new long[]{featureId});
                        final FutureTask<List<Popup>> identifyFuture = mapController.queryFeatureLayer(featureLayer, queryParameters);
                        Executors.newSingleThreadExecutor().submit(identifyFuture);
                        try {
                            final List<Popup> popups = identifyFuture.get();
                            if (1 == popups.size()) {
                                if (null != addFeatureListener) {
                                    addFeatureListener.featureAdded(popups.get(0));
                                } else {
                                    Log.w(TAG, getString(R.string.no_add_feature_listener, activity.getClass().getName()));
                                }
                            } else {
                                Log.w(TAG, getString(R.string.feature_id_query_expected_single_result, featureId, popups.size()));
                            }
                        } catch (InterruptedException | ExecutionException e) {
                            Log.e(TAG, "Exception while identifying feature layers", e);
                        }
                    }
                }
            });
        }
    }

    private void exitEditMode() {
        geometryEditController.setEditMode(GeometryEditController.EditMode.NONE);
        clear();
        mapController.setShowMagnifierOnLongPress(false);
    }

    private void clear() {
        // Clear feature editing data
        geometryEditController.setCurrentEditingState(new EditingState());
        midPoints.clear();
        geometryEditController.clearEditingStates();

        if (graphicsLayerEditing != null) {
            graphicsLayerEditing.removeAll();
        }

        updateActionBar();
    }

}
