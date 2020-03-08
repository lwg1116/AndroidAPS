package info.nightscout.androidaps.plugins.pump.danaR.services;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Date;

import javax.inject.Inject;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.dialogs.BolusProgressDialog;
import info.nightscout.androidaps.events.EventAppExit;
import info.nightscout.androidaps.events.EventInitializationChanged;
import info.nightscout.androidaps.events.EventPreferenceChange;
import info.nightscout.androidaps.events.EventProfileNeedsUpdate;
import info.nightscout.androidaps.events.EventPumpStatusChanged;
import info.nightscout.androidaps.interfaces.ActivePluginProvider;
import info.nightscout.androidaps.interfaces.CommandQueueProvider;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.bus.RxBusWrapper;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventOverviewBolusProgress;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.pump.danaR.DanaRPlugin;
import info.nightscout.androidaps.plugins.pump.danaR.DanaRPump;
import info.nightscout.androidaps.plugins.pump.danaR.SerialIOThread;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MessageBase;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MessageHashTableR;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgBolusProgress;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgBolusStart;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgBolusStartWithSpeed;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgBolusStop;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgCheckValue;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetActivateBasalProfile;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetBasalProfile;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetCarbsEntry;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetExtendedBolusStart;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetExtendedBolusStop;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetTempBasalStart;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetTempBasalStop;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetTime;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSetUserOptions;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingActiveProfile;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingBasal;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingGlucose;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingMaxValues;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingMeal;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingProfileRatios;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingProfileRatiosAll;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingPumpTime;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingShippingInfo;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgSettingUserOptions;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgStatus;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgStatusBasic;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgStatusBolusExtended;
import info.nightscout.androidaps.plugins.pump.danaR.comm.MsgStatusTempBasal;
import info.nightscout.androidaps.plugins.pump.danaR.events.EventDanaRNewStatus;
import info.nightscout.androidaps.plugins.pump.danaRKorean.DanaRKoreanPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.queue.Callback;
import info.nightscout.androidaps.queue.commands.Command;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.SP;
import info.nightscout.androidaps.utils.resources.ResourceHelper;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class DanaRExecutionService extends AbstractDanaRExecutionService {
    @Inject AAPSLogger aapsLogger;
    @Inject RxBusWrapper rxBus;
    @Inject ResourceHelper resourceHelper;
    @Inject ConstraintChecker constraintChecker;
    @Inject DanaRPump danaRPump;
    @Inject DanaRPlugin danaRPlugin;
    @Inject DanaRKoreanPlugin danaRKoreanPlugin;
    @Inject ConfigBuilderPlugin configBuilderPlugin;
    @Inject CommandQueueProvider commandQueue;
    @Inject Context context;
    @Inject MessageHashTableR messageHashTableR;
    @Inject ActivePluginProvider activePlugin;

    private CompositeDisposable disposable = new CompositeDisposable();

    public DanaRExecutionService() {

    }

    @Override
    public void onCreate() {
        super.onCreate();
        mBinder = new LocalBinder();
        context.registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
        disposable.add(rxBus
                .toObservable(EventPreferenceChange.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> {
                    if (mSerialIOThread != null)
                        mSerialIOThread.disconnect("EventPreferenceChange");
                }, exception -> FabricPrivacy.getInstance().logException(exception))
        );
        disposable.add(rxBus
                .toObservable(EventAppExit.class)
                .observeOn(Schedulers.io())
                .subscribe(event -> {
                    aapsLogger.debug(LTag.PUMP, "EventAppExit received");
                    if (mSerialIOThread != null)
                        mSerialIOThread.disconnect("Application exit");
                    context.unregisterReceiver(receiver);
                    stopSelf();
                }, exception -> FabricPrivacy.getInstance().logException(exception))
        );
    }

    @Override
    public void onDestroy() {
        disposable.clear();
        super.onDestroy();
    }

    public class LocalBinder extends Binder {
        public DanaRExecutionService getServiceInstance() {
            return DanaRExecutionService.this;
        }
    }

    public void connect() {
        if (mConnectionInProgress)
            return;

        new Thread(() -> {
            mHandshakeInProgress = false;
            mConnectionInProgress = true;
            getBTSocketForSelectedPump();
            if (mRfcommSocket == null || mBTDevice == null) {
                mConnectionInProgress = false;
                return; // Device not found
            }

            try {
                mRfcommSocket.connect();
            } catch (IOException e) {
                //log.error("Unhandled exception", e);
                if (e.getMessage().contains("socket closed")) {
                    aapsLogger.error("Unhandled exception", e);
                }
            }

            if (isConnected()) {
                if (mSerialIOThread != null) {
                    mSerialIOThread.disconnect("Recreate SerialIOThread");
                }
                mSerialIOThread = new SerialIOThread(mRfcommSocket, messageHashTableR, danaRPump);
                mHandshakeInProgress = true;
                rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.HANDSHAKING, 0));
            }

            mConnectionInProgress = false;
        }).start();
    }

    public void getPumpStatus() {
        try {
            rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.gettingpumpstatus)));
            MsgStatus statusMsg = new MsgStatus(aapsLogger, danaRPump);
            MsgStatusBasic statusBasicMsg = new MsgStatusBasic(aapsLogger, danaRPump);
            MsgStatusTempBasal tempStatusMsg = new MsgStatusTempBasal(aapsLogger, danaRPump, activePlugin);
            MsgStatusBolusExtended exStatusMsg = new MsgStatusBolusExtended(aapsLogger, danaRPump, activePlugin);
            MsgCheckValue checkValue = new MsgCheckValue(aapsLogger, danaRPump, danaRPlugin);

            if (danaRPump.isNewPump()) {
                mSerialIOThread.sendMessage(checkValue);
                if (!checkValue.received) {
                    return;
                }
            }

            mSerialIOThread.sendMessage(statusMsg);
            mSerialIOThread.sendMessage(statusBasicMsg);
            rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.gettingtempbasalstatus)));
            mSerialIOThread.sendMessage(tempStatusMsg);
            rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.gettingextendedbolusstatus)));
            mSerialIOThread.sendMessage(exStatusMsg);
            rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.gettingbolusstatus)));

            long now = System.currentTimeMillis();
            danaRPump.setLastConnection(now);

            Profile profile = ProfileFunctions.getInstance().getProfile();
            if (profile != null && Math.abs(danaRPump.getCurrentBasal() - profile.getBasal()) >= danaRPlugin.getPumpDescription().basalStep) {
                rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.gettingpumpsettings)));
                mSerialIOThread.sendMessage(new MsgSettingBasal(aapsLogger, danaRPump, danaRPlugin));
                if (!danaRPlugin.isThisProfileSet(profile) && !commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)) {
                    rxBus.send(new EventProfileNeedsUpdate());
                }
            }

            if (danaRPump.getLastSettingsRead() + 60 * 60 * 1000L < now || !danaRPlugin.isInitialized()) {
                rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.gettingpumpsettings)));
                mSerialIOThread.sendMessage(new MsgSettingShippingInfo(aapsLogger, danaRPump));
                mSerialIOThread.sendMessage(new MsgSettingActiveProfile(aapsLogger, danaRPump));
                mSerialIOThread.sendMessage(new MsgSettingMeal(aapsLogger, rxBus, resourceHelper, danaRPump, danaRKoreanPlugin));
                mSerialIOThread.sendMessage(new MsgSettingBasal(aapsLogger, danaRPump, danaRPlugin));
                //0x3201
                mSerialIOThread.sendMessage(new MsgSettingMaxValues(aapsLogger, danaRPump));
                mSerialIOThread.sendMessage(new MsgSettingGlucose(aapsLogger, danaRPump));
                mSerialIOThread.sendMessage(new MsgSettingActiveProfile(aapsLogger, danaRPump));
                mSerialIOThread.sendMessage(new MsgSettingProfileRatios(aapsLogger, danaRPump));
                mSerialIOThread.sendMessage(new MsgSettingProfileRatiosAll(aapsLogger, danaRPump));
                mSerialIOThread.sendMessage(new MsgSettingUserOptions(aapsLogger, danaRPump));
                rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.gettingpumptime)));
                mSerialIOThread.sendMessage(new MsgSettingPumpTime(aapsLogger, danaRPump));
                if (danaRPump.getPumpTime() == 0) {
                    // initial handshake was not successfull
                    // deinitialize pump
                    danaRPump.setLastConnection(0);
                    danaRPump.setLastSettingsRead(0);
                    rxBus.send(new EventDanaRNewStatus());
                    rxBus.send(new EventInitializationChanged());
                    return;
                }
                long timeDiff = (danaRPump.getPumpTime() - System.currentTimeMillis()) / 1000L;
                aapsLogger.debug(LTag.PUMP, "Pump time difference: " + timeDiff + " seconds");
                if (Math.abs(timeDiff) > 10) {
                    mSerialIOThread.sendMessage(new MsgSetTime(new Date()));
                    mSerialIOThread.sendMessage(new MsgSettingPumpTime(aapsLogger, danaRPump));
                    timeDiff = (danaRPump.getPumpTime() - System.currentTimeMillis()) / 1000L;
                    aapsLogger.debug(LTag.PUMP, "Pump time difference: " + timeDiff + " seconds");
                }
                danaRPump.setLastSettingsRead(now);
            }

            rxBus.send(new EventDanaRNewStatus());
            rxBus.send(new EventInitializationChanged());
            //NSUpload.uploadDeviceStatus();
            if (danaRPump.getDailyTotalUnits() > danaRPump.getMaxDailyTotalUnits() * Constants.dailyLimitWarning) {
                aapsLogger.debug(LTag.PUMP, "Approaching daily limit: " + danaRPump.getDailyTotalUnits() + "/" + danaRPump.getMaxDailyTotalUnits());
                if (System.currentTimeMillis() > lastApproachingDailyLimit + 30 * 60 * 1000) {
                    Notification reportFail = new Notification(Notification.APPROACHING_DAILY_LIMIT, resourceHelper.gs(R.string.approachingdailylimit), Notification.URGENT);
                    rxBus.send(new EventNewNotification(reportFail));
                    NSUpload.uploadError(resourceHelper.gs(R.string.approachingdailylimit) + ": " + danaRPump.getDailyTotalUnits() + "/" + danaRPump.getMaxDailyTotalUnits() + "U");
                    lastApproachingDailyLimit = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            aapsLogger.error("Unhandled exception", e);
        }
    }

    public boolean tempBasal(int percent, int durationInHours) {
        if (!isConnected()) return false;
        if (danaRPump.isTempBasalInProgress()) {
            rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.stoppingtempbasal)));
            mSerialIOThread.sendMessage(new MsgSetTempBasalStop());
            SystemClock.sleep(500);
        }
        rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.settingtempbasal)));
        mSerialIOThread.sendMessage(new MsgSetTempBasalStart(percent, durationInHours));
        mSerialIOThread.sendMessage(new MsgStatusTempBasal(aapsLogger, danaRPump, activePlugin));
        rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING));
        return true;
    }

    public boolean tempBasalStop() {
        if (!isConnected()) return false;
        rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.stoppingtempbasal)));
        mSerialIOThread.sendMessage(new MsgSetTempBasalStop());
        mSerialIOThread.sendMessage(new MsgStatusTempBasal(aapsLogger, danaRPump, activePlugin));
        rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING));
        return true;
    }

    public boolean extendedBolus(double insulin, int durationInHalfHours) {
        if (!isConnected()) return false;
        rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.settingextendedbolus)));
        mSerialIOThread.sendMessage(new MsgSetExtendedBolusStart(aapsLogger, constraintChecker, insulin, (byte) (durationInHalfHours & 0xFF)));
        mSerialIOThread.sendMessage(new MsgStatusBolusExtended(aapsLogger, danaRPump, activePlugin));
        rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING));
        return true;
    }

    public boolean extendedBolusStop() {
        if (!isConnected()) return false;
        rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.stoppingextendedbolus)));
        mSerialIOThread.sendMessage(new MsgSetExtendedBolusStop());
        mSerialIOThread.sendMessage(new MsgStatusBolusExtended(aapsLogger, danaRPump, activePlugin));
        rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING));
        return true;
    }

    @Override
    public PumpEnactResult loadEvents() {
        return null;
    }

    public boolean bolus(double amount, int carbs, long carbtime, final Treatment t) {
        if (!isConnected()) return false;
        if (BolusProgressDialog.stopPressed) return false;

        mBolusingTreatment = t;
        int preferencesSpeed = SP.getInt(R.string.key_danars_bolusspeed, 0);
        MessageBase start;
        if (preferencesSpeed == 0)
            start = new MsgBolusStart(aapsLogger, constraintChecker, danaRPump, amount);
        else
            start = new MsgBolusStartWithSpeed(aapsLogger, constraintChecker, danaRPump, amount, preferencesSpeed);
        MsgBolusStop stop = new MsgBolusStop(amount, t);

        if (carbs > 0) {
            mSerialIOThread.sendMessage(new MsgSetCarbsEntry(carbtime, carbs));
        }

        if (amount > 0) {
            MsgBolusProgress progress = new MsgBolusProgress(amount, t); // initialize static variables
            long bolusStart = System.currentTimeMillis();

            if (!stop.stopped) {
                mSerialIOThread.sendMessage(start);
            } else {
                t.insulin = 0d;
                return false;
            }
            while (!stop.stopped && !start.failed) {
                SystemClock.sleep(100);
                if ((System.currentTimeMillis() - progress.lastReceive) > 15 * 1000L) { // if i didn't receive status for more than 15 sec expecting broken comm
                    stop.stopped = true;
                    stop.forced = true;
                    aapsLogger.debug(LTag.PUMP, "Communication stopped");
                }
            }
            SystemClock.sleep(300);

            EventOverviewBolusProgress bolusingEvent = EventOverviewBolusProgress.INSTANCE;
            bolusingEvent.setT(t);
            bolusingEvent.setPercent(99);

            mBolusingTreatment = null;

            int speed = 12;
            switch (preferencesSpeed) {
                case 0:
                    speed = 12;
                    break;
                case 1:
                    speed = 30;
                    break;
                case 2:
                    speed = 60;
                    break;
            }
            // try to find real amount if bolusing was interrupted or comm failed
            if (t.insulin != amount) {
                disconnect("bolusingInterrupted");
                long bolusDurationInMSec = (long) (amount * speed * 1000);
                long expectedEnd = bolusStart + bolusDurationInMSec + 3000;

                while (System.currentTimeMillis() < expectedEnd) {
                    long waitTime = expectedEnd - System.currentTimeMillis();
                    bolusingEvent.setStatus(String.format(resourceHelper.gs(R.string.waitingforestimatedbolusend), waitTime / 1000));
                    rxBus.send(bolusingEvent);
                    SystemClock.sleep(1000);
                }

                final Object o = new Object();
                synchronized (o) {
                    commandQueue.independentConnect("bolusingInterrupted", new Callback() {
                        @Override
                        public void run() {
                            if (danaRPump.getLastBolusTime() > System.currentTimeMillis() - 60 * 1000L) { // last bolus max 1 min old
                                t.insulin = danaRPump.getLastBolusAmount();
                                aapsLogger.debug(LTag.PUMP, "Used bolus amount from history: " + danaRPump.getLastBolusAmount());
                            } else {
                                aapsLogger.debug(LTag.PUMP, "Bolus amount in history too old: " + DateUtil.dateAndTimeString(danaRPump.getLastBolusTime()));
                            }
                            synchronized (o) {
                                o.notify();
                            }
                        }
                    });
                    try {
                        o.wait();
                    } catch (InterruptedException e) {
                        aapsLogger.error("Unhandled exception", e);
                    }
                }
            } else {
                commandQueue.readStatus("bolusOK", null);
            }
        }
        return !start.failed;
    }

    public boolean carbsEntry(int amount) {
        if (!isConnected()) return false;
        MsgSetCarbsEntry msg = new MsgSetCarbsEntry(System.currentTimeMillis(), amount);
        mSerialIOThread.sendMessage(msg);
        return true;
    }

    @Override
    public boolean highTempBasal(int percent) {
        return false;
    }

    @Override
    public boolean tempBasalShortDuration(int percent, int durationInMinutes) {
        return false;
    }

    public boolean updateBasalsInPump(final Profile profile) {
        if (!isConnected()) return false;
        rxBus.send(new EventPumpStatusChanged(resourceHelper.gs(R.string.updatingbasalrates)));
        Double[] basal = danaRPump.buildDanaRProfileRecord(profile);
        MsgSetBasalProfile msgSet = new MsgSetBasalProfile((byte) 0, basal);
        mSerialIOThread.sendMessage(msgSet);
        MsgSetActivateBasalProfile msgActivate = new MsgSetActivateBasalProfile((byte) 0);
        mSerialIOThread.sendMessage(msgActivate);
        danaRPump.setLastSettingsRead(0); // force read full settings
        getPumpStatus();
        rxBus.send(new EventPumpStatusChanged(EventPumpStatusChanged.Status.DISCONNECTING));
        return true;
    }

    public PumpEnactResult setUserOptions() {
        if (!isConnected())
            return new PumpEnactResult(injector).success(false);
        SystemClock.sleep(300);
        MsgSetUserOptions msg = new MsgSetUserOptions(aapsLogger, danaRPump);
        mSerialIOThread.sendMessage(msg);
        SystemClock.sleep(200);
        return new PumpEnactResult(injector).success(!msg.failed);
    }
}
