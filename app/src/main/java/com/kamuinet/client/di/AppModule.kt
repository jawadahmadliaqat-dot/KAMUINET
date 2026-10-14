package com.kamuinet.client.di

import android.content.Context
import androidx.room.Room
import com.kamuinet.client.data.AppDatabase
import com.kamuinet.client.data.UsageDao
import com.kamuinet.client.supabase.KamuiSupabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        return KamuiSupabase.client
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "kamui_db"
        ).build()
    }

    @Provides
    fun provideUsageDao(db: AppDatabase): UsageDao {
        return db.usageDao()
    }

    @Provides
    @Singleton
    fun provideAppConfigRepository(@ApplicationContext context: Context): com.kamuinet.client.data.AppConfigRepository {
        return com.kamuinet.client.data.AppConfigRepository(context)
    }
}
