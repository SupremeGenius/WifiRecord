package com.cogn.wifirecord;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class to estimate location of user based on Wifi readings.
 */
public class RecordForLocation implements SensorEventListener {
    private static final String TAG = "WIFI_LOCATE";
    private final ReadingSummaryList summaryList;
    private RecordActivity callingActivity;
    private ProvidesWifiScan wifiScanner;
    private ConnectionPoints connectionPoints;
    private int nearestConnectionIndex;
    private boolean scanRunning = false;
    private long delayMS = 100;

    //adjustable parameters
    private float pxPerM;
    private float walking_pace =  2.0f; // m/s FAST: 7.6km/h;
    private float errorAccomodationM = 20.0f; // Distance that is allowed to move in zero time
    int lengthMovingObs = 3;
    int minLengthStationaryObs = 5;
    int maxLengthStationaryObs = 20;
    boolean updateForSamePos = false;
    private float stickyMinImprovment = 5.0f; // The amount by which the new score must be better than the last during the sticky period
    private int stickyMaxTime = 3000;

    private double mAccel;
    private double mAccelCurrent;
    private double mAccelLast;
    private boolean resetSinceMoveQueue;

    //For location
    private long offset;
    private float currentX; // where the circle is currently drawn
    private float currentY;
    private float bestFitX; // the current best guess place
    private float bestFitY;
    private int bestFitLevel;
    private int bestFitIndex;
    private long bestFitTime;
    private ReadingsQueue m_shortQueue;
    private ReadingsQueue m_sinceMoveQueue;

    // Values for drifting circle
    private long prevTime = 0;
    private float dx = 0;
    private float dy = 0;
    private float pxPerDelay;

    public RecordForLocation(String location, ConnectionPoints connectionPoints, float pxPerM,
                             ReadingSummaryList summaryList,
                             RecordActivity callingActivity, ProvidesWifiScan wifiScanner) {
        this.connectionPoints = connectionPoints;
        this.pxPerM = pxPerM;
        pxPerDelay = walking_pace*pxPerM * delayMS/1000;
        this.summaryList = summaryList;
        this.callingActivity = callingActivity;
        this.wifiScanner = wifiScanner;
        mAccel = 0.00f;
        mAccelCurrent = SensorManager.GRAVITY_EARTH;
        mAccelLast = SensorManager.GRAVITY_EARTH;
    }

    public void Stop() {
        scanRunning = false;
        //Log.d(TAG, "SCAN STOPPED");
    }


    private boolean HaveChanged(SparseArray<Float> oldResults, SparseArray<Float> results) {
        if (oldResults==null) return true;
        if (oldResults.size()!=results.size()) return true;
        for (int i = 0; i<oldResults.size(); i++)
        {
            if ((Math.abs(oldResults.valueAt(i)-results.valueAt(i))>1e-9)) return true;
        }
        return false;
    }

    private void UpdateMarkedLocation(boolean checkDirection)
    {
        if (prevTime==0){
            prevTime = offset;
            currentX = bestFitX;
            currentY = bestFitY;
            return;
        }
        if (checkDirection) {
            if (Math.abs(bestFitX -currentX)<pxPerDelay/2 && Math.abs(bestFitY -currentY)<pxPerDelay/2) {
                currentX = bestFitX;
                currentY = bestFitY;
                dx = 0;
                dy = 0;
            } else {
                double theta = Math.atan((bestFitY -currentY)/(bestFitX -currentX));
                if ((bestFitX -currentX)<0) theta+= Math.PI;
                //TODO: check if I need to handle theta = +-pi/2
                dx = pxPerDelay*(float)Math.cos(theta);
                dy = pxPerDelay*(float)Math.sin(theta);
            }
        } else {
            if (Math.abs(bestFitX - currentX) < pxPerDelay / 2 && Math.abs(bestFitY - currentY) < pxPerDelay / 2) {
                currentX = bestFitX;
                currentY = bestFitY;
                dx = 0;
                dy = 0;
            } else {
                currentX += dx;
                currentY += dy;
            }
        }
    }



    public void StartScanning(){
        resetSinceMoveQueue = false;
        SparseArray<Float> oldResults = null;
        SparseArray<Float> results;
        long startTimeMillis = Calendar.getInstance().getTimeInMillis();
        m_shortQueue = new ReadingsQueue(lengthMovingObs);
        m_sinceMoveQueue = new ReadingsQueue(maxLengthStationaryObs);
        List<String> scores = null;
        bestFitIndex = -1;

        while (scanRunning){
            offset = Calendar.getInstance().getTimeInMillis() - startTimeMillis;
            results = wifiScanner.getScanResults(offset);
            if (HaveChanged(oldResults, results)) {
                // Add the scan to the Queues
                m_shortQueue.AddNew(offset);
                if (resetSinceMoveQueue) {
                    m_sinceMoveQueue.clear();
                    resetSinceMoveQueue = false;
                    //Log.d(TAG, "Reset sinceMoveQueue");
                }
                m_sinceMoveQueue.AddNew(offset);
                //Log.d(TAG, "OFFSET," + offset+"\n");
                oldResults = results.clone();
                for (int i = 0; i<results.size(); i++) {
                    //Log.d(TAG, macID + "," + scan.level+"\n");
                    m_shortQueue.UpdateEnd(results.keyAt(i), results.valueAt(i));
                    m_sinceMoveQueue.UpdateEnd(results.keyAt(i), results.valueAt(i));
                }

                // Find the best fit location, sets bestFitX and bestFitY
                UpdateBestFit();
                // Check which direction the bestGuess should move
                UpdateMarkedLocation(true);
                // Get the scores to display on the floorMap
                scores = summaryList.GetScores(callingActivity.GetLevelID()).scores;
            } else {
                UpdateMarkedLocation(false); // No new reading, just drift the circle if required.
            }

            if (scores!=null) UpdateOnUIThread(scores, currentX, currentY);

            try { Thread.sleep(delayMS); }
            catch (InterruptedException e) {
                e.printStackTrace();
                scanRunning = false;
            }
        }
    }

    private void UpdateBestFit() {
        // device has not been moving.  Use the long queue.  Should be more accurate
        if (m_sinceMoveQueue.size()>minLengthStationaryObs) {
            SetMovementStatusOnUIThread("Stationary");
            UpdateBestFitFromQueue(m_sinceMoveQueue, "m_sinceMoveQueue");
        }
        // device has moved, use the short queue
        else {
            SetMovementStatusOnUIThread("Moving");
            UpdateBestFitFromQueue(m_shortQueue, "m_shortQueue");
        }
    }

    /**
     * Only if score is good enough in absolute sense and offers a big enough improvement over the previous location
     * @param queue which queue to use in makeing the summary.  shortQueue of recent recordings or long one since last move.
     * @param description log which queue is being used
     */
    private void UpdateBestFitFromQueue(ReadingsQueue queue, String description){
        HashMap<Integer, List<Float>> observationSummary;
        ReadingSummaryList.ReadingSummary locationSummary;

        // Find the unconstrained best fit
        observationSummary = queue.GetSummary();
        summaryList.UpdateScores(observationSummary);
        int maxIndex = -1;
        float maxScore = -1e9f;
        for (int i = 0; i<summaryList.summaryList.size(); i++) {
            locationSummary = summaryList.summaryList.get(i);
            if (locationSummary.score>maxScore){
                maxIndex = i;
                maxScore = locationSummary.score;
            }
        }
        // Decide if the best fit is good enough to use


        boolean updatePos = false;
        // No location is recorded yet, update
        if (bestFitIndex < 0) {
            updatePos = true;
        }
        // At the same place, only consider updating is the score has improved,
        // otherwise we could be on our way to somewhere else and we anchor this point too strongly
        else if (bestFitIndex==maxIndex) {
            updatePos = maxScore > summaryList.summaryList.get(bestFitIndex).score && updateForSamePos;
            Log.d(TAG, "Same place");
        }
        // Have not been at current location long and new location does not offer a significant
        // impovement.  So don't update.
        else if (maxScore<(summaryList.summaryList.get(bestFitIndex).score + stickyMinImprovment) && (offset-bestFitTime)<=stickyMaxTime){
            updatePos = false;
        }
        // Default case, there is a better score at a new location. Check whether it is reasonable
        // that we could have walked there in the time since the current location was recorded.
        else {
            // Find the distance to the position with the best score
            float x = summaryList.summaryList.get(maxIndex).x;
            float y = summaryList.summaryList.get(maxIndex).y;
            int level = summaryList.summaryList.get(maxIndex).level;
            double dist_px;
            if (level == bestFitLevel) {
                dist_px = Math.sqrt((x - bestFitX) * (x - bestFitX) + (y - bestFitY) * (x - bestFitY));
            } else {
                float dx0 = connectionPoints.getX(nearestConnectionIndex, bestFitLevel) - bestFitX;
                float dy0 = connectionPoints.getY(nearestConnectionIndex, bestFitLevel) - bestFitY;
                float dx1 = connectionPoints.getX(nearestConnectionIndex, level) - x;
                float dy1 = connectionPoints.getY(nearestConnectionIndex, level) - y;
                dist_px = Math.sqrt(dx0 * dx0 + dy0 * dy0) + Math.sqrt(dx1 * dx1 + dy1 * dy1);
            }
            double timeToThere = (((dist_px / pxPerM) - errorAccomodationM) / walking_pace) * 1000;

            if (timeToThere < (offset - bestFitTime)) {
                updatePos = true;
                Log.d(TAG, "Updated because timeToThere=" + timeToThere + " and we have been here for " + (offset - bestFitTime));
            }
            else {
                Log.d(TAG, "Not updated because timeToThere=" + timeToThere + " and we have been here for " + (offset - bestFitTime));
            }
        }
        // Criteria met for position to be updated.
        if (updatePos) {
            bestFitTime = offset;
            bestFitX = summaryList.summaryList.get(maxIndex).x;
            bestFitY = summaryList.summaryList.get(maxIndex).y;
            bestFitIndex = maxIndex;
            nearestConnectionIndex = connectionPoints.IndexOfClosest(bestFitLevel, bestFitX, bestFitY);
            if (callingActivity.GetLevelID()!= summaryList.summaryList.get(maxIndex).level) {
                //Log.d(TAG, "Level changed to " + summaryList.summaryList.get(maxIndex).level);
                bestFitLevel = summaryList.summaryList.get(maxIndex).level;
                SetLevelOnUIThread(bestFitLevel);
                currentX = bestFitX; // Circle does not need to drift accross levels.
                currentY = bestFitY;
            }
        }
    }

    private void SetMovementStatusOnUIThread(final String movementStatus) {
        callingActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                callingActivity.UpdateMovementStatus(movementStatus);
            }
        });
    }

    /**
     * Set the image to display.  Called when best fit level is different from currently displayed.
     * Only use this if there has been a change.
     * @param bestFitLevel - value of floor level.
     */
    private void SetLevelOnUIThread(final int bestFitLevel) {
        callingActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                callingActivity.SetLevel(bestFitLevel);
            }
        });
    }

    private void UpdateOnUIThread(final List<String> scores, final float bestGuessX, final float bestGuessY)
    {
        callingActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                callingActivity.UpdateLocateProgress(scores, bestGuessX, bestGuessY);
            }
        });
    }

    public void Start() {
        scanRunning = true;
        Log.d(TAG, "SCAN STARTED");
        new Thread(new Runnable() {
            public void run() {
                StartScanning();
            }
        }).start();
    }



    private class ReadingsQueue{

        ArrayDeque<SparseArray<Float>> values;
        int maxLength;

        public ReadingsQueue(int maxLength)
        {
            this.maxLength = maxLength;
            values = new ArrayDeque<>();

        }

        /**
         * Returns the number of elements in this deque.
         */
        public int size(){ return values.size(); }

        /**
         * Clears the contents of the Queue
         */
        public void clear() { values.clear(); }

        /**
         * Adds a new empty record to the end of the queue and removes any records from the start
         * that are too far in the past.
         * @param latestTime the time since recording started (in ms)
         */
        public void AddNew(long latestTime)
        {
            values.addLast(new SparseArray<Float>());
            if (values.size()>maxLength) {
                values.removeFirst();
            }
        }

        public void UpdateEnd(Integer macID, Float reading)
        {
            values.peekLast().put(macID, reading);
        }

        private float GetMean(List<Float> values) {
            double total = 0.0;
            for (Float value : values)
                total += value;
            return (float)(total/values.size());
        }

        /**
         * Population standard deviation in case there is only one entry point
         */
        private float GetStd(List<Float> values) {
            double total = 0.0;
            float mean = GetMean(values);
            for (Float value : values)
                total += (value-mean)*(value-mean);
            return (float)Math.sqrt(total/values.size());
        }


        public HashMap<Integer, List<Float>> GetSummary() {
            HashMap<Integer, ArrayList<Float>> aggregate = new HashMap<>();
            for (SparseArray<Float> value : values)
            {
                for (int i=0; i<value.size(); i++) {
                //for (Map.Entry<Integer, Float> entry : value. value.entrySet()) {
                    if (!aggregate.containsKey(value.keyAt(i))) {
                        aggregate.put(value.keyAt(i), new ArrayList<>(Arrays.asList(value.valueAt(i))));
                    }
                    else {
                        aggregate.get(value.keyAt(i)).add(value.valueAt(i));
                    }
                }
            }
            HashMap<Integer, List<Float>> summary = new HashMap<>();
            float p;
            float mu;
            float sigma;
            for (Map.Entry<Integer, ArrayList<Float>> aggregateEntry : aggregate.entrySet()) {
                p = aggregateEntry.getValue().size()/values.size();
                mu = GetMean(aggregateEntry.getValue());
                sigma = GetStd(aggregateEntry.getValue());
                summary.put(aggregateEntry.getKey(), new ArrayList<>(Arrays.asList(p, mu, sigma)));
            }
            return summary;
        }
    }
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float[] mGravity = event.values.clone();
            // Shake detection
            float x = mGravity[0];
            float y = mGravity[1];
            float z = mGravity[2];
            mAccelLast = mAccelCurrent;
            mAccelCurrent = Math.sqrt(x * x + y * y + z * z);

            double delta = mAccelCurrent - mAccelLast;
            mAccel = mAccel * 0.9 + delta;
            // Make this higher or lower according to how much
            // motion you want to detect
            if(mAccel > 0.5){
                resetSinceMoveQueue = true;
            }
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

}
