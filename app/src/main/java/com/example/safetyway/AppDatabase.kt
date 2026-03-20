package com.example.safetyway
// 앱에 탑재된 실제 DB를 대변.
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 1. 사용할 Entity들을 리스트로 넣고, 데이터베이스 버전을 지정합니다.
@Database(entities = [CctvEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {

    // 2. Dao를 가져올 수 있는 추상 함수를 선언합니다.
    abstract fun cctvDao(): CctvDao

    companion object {
        // 3. 앱 전체에서 공유할 단 하나의 데이터베이스 인스턴스 (싱글톤)
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // 인스턴스가 이미 있으면 그것을 반환하고, 없으면 새로 만듭니다.
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "safety_way_db" // 생성될 데이터베이스 파일 이름
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}