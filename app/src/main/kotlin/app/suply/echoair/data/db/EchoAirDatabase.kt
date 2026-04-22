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
    version = 1,
    exportSchema = false
)
abstract class EchoAirDatabase : RoomDatabase() {
    abstract fun shipments(): ShipmentDao
    abstract fun devices(): DeviceDao
    abstract fun records(): RecordDao
    abstract fun uploads(): PendingUploadDao
}
