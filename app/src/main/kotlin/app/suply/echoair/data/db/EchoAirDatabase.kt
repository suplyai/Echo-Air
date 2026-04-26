package app.suply.echoair.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedShipment::class,
        CachedDevice::class,
        CachedUnit::class,
        TemperatureRecord::class,
        PendingUpload::class
    ],
    // v2: CachedShipment adds originCity / destCity.
    // v3: Adds CachedUnit + CachedDevice.unitId for Multiple Package
    //     Shipment (MPS) support. DataModule still uses
    //     .fallbackToDestructiveMigration(), so the cache rebuilds on
    //     upgrade — acceptable because the app re-fetches shipments
    //     from the API on every lookup anyway.
    version = 3,
    exportSchema = false
)
abstract class EchoAirDatabase : RoomDatabase() {
    abstract fun shipments(): ShipmentDao
    abstract fun devices(): DeviceDao
    abstract fun units(): UnitDao
    abstract fun records(): RecordDao
    abstract fun uploads(): PendingUploadDao
}
