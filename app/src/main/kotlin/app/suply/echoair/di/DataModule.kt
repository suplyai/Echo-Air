package app.suply.echoair.di

import android.content.Context
import androidx.room.Room
import app.suply.echoair.data.db.EchoAirDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): EchoAirDatabase =
        Room.databaseBuilder(context, EchoAirDatabase::class.java, "echoair.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideShipmentDao(db: EchoAirDatabase) = db.shipments()
    @Provides fun provideDeviceDao(db: EchoAirDatabase) = db.devices()
    @Provides fun provideRecordDao(db: EchoAirDatabase) = db.records()
    @Provides fun providePendingUploadDao(db: EchoAirDatabase) = db.uploads()
}
