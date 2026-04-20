package com.healthcare.app.di

import com.healthcare.app.util.CalorieCalculator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideCalorieCalculator(): CalorieCalculator {
        return CalorieCalculator()
    }
}
