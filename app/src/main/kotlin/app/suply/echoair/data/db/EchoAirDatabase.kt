package app.suply.echoair.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedShipment::class,
        CachedDevice::class,
        TemperatureRecord::class,
        PendingUpload::class
    ],
    // v2: CachedShipment adds originCity / destCity. DataModule uses
    //     .fallbackToDestructiveMigration(), so the cache is rebuilt on
    //     upgrade — acceptable because the app re-fetches shipments
    //     from the API on every lookup anyway.
    version = 2,
    exportSchema = false
)
abstract class EchoAirDatabase : RoomDatabase() {
    abstract fun shipments(): ShipmentDao
    abstract fun devices(): DeviceDao
    abstract fun records(): RecordDao
    abstract fun uploads(): PendingUploadDao
}
