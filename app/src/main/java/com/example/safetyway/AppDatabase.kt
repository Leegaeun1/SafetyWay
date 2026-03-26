package com.example.safetyway
// 앱에 탑재된 실제 DB를 대변.
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CctvEntity::class], version = 1, exportSchema = false) // 어떤 엔티티 사용? 버전 몇?
abstract class AppDatabase : RoomDatabase() {
    abstract fun cctvDao(): CctvDao

    companion object { // 싱글톤 패턴
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cctv_database" // 앱 내부에 저장될 실제 DB 이름
                )
                    .createFromAsset("cctv_data.db") // assets의 DB 파일을 복사해서 가져옴
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}