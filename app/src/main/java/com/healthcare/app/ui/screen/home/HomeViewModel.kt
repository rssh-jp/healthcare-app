package com.healthcare.app.ui.screen.home

import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseUser
import com.healthcare.app.data.entity.SyncStatus
import com.healthcare.app.data.entity.WalkingSession
import com.healthcare.app.data.repository.AuthRepository
import com.healthcare.app.data.repository.FirestoreSyncRepository
import com.healthcare.app.data.repository.WalkingRepository
import com.healthcare.app.service.LocationTrackingService
import com.healthcare.app.sync.SyncWorker
import com.healthcare.app.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class HomeUiState(
    val todayDistance: Double = 0.0,
    val todayCalories: Double = 0.0,
    val todaySessionCount: Int = 0,
    val recentSessions: List<WalkingSession> = emptyList(),
    val isTracking: Boolean = false,
    val currentDistance: Double = 0.0,
    val currentCalories: Double = 0.0,
    val currentUser: FirebaseUser? = null,
    val isSigningIn: Boolean = false,
    val authError: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkingRepository,
    private val authRepository: AuthRepository,
    private val firestoreSyncRepository: FirestoreSyncRepository,
    val googleSignInClient: GoogleSignInClient
) : ViewModel() {

    private val todayStart = DateUtils.getStartOfDay(LocalDate.now())
    private val todayEnd = DateUtils.getEndOfDay(LocalDate.now())

    private val _uiState = MutableStateFlow(HomeUiState(currentUser = authRepository.currentUser))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        combine(
            repository.getTotalDistanceByDateRange(todayStart, todayEnd),
            repository.getTotalCaloriesByDateRange(todayStart, todayEnd),
            repository.getSessionCountByDateRange(todayStart, todayEnd),
            repository.getSessionsByDateRange(todayStart, todayEnd),
            LocationTrackingService.isTracking
        ) { distance, calories, count, sessions, tracking ->
            _uiState.update { cur ->
                cur.copy(
                    todayDistance = distance,
                    todayCalories = calories,
                    todaySessionCount = count,
                    recentSessions = sessions.take(5),
                    isTracking = tracking,
                    currentDistance = LocationTrackingService.totalDistanceFlow.value,
                    currentCalories = LocationTrackingService.totalCaloriesFlow.value
                )
            }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            authRepository.authState.collect { user ->
                _uiState.update { it.copy(currentUser = user) }
            }
        }
    }

    fun onSignInResult(result: ActivityResult) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSigningIn = true, authError = null) }
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken
                if (idToken == null) {
                    _uiState.update { it.copy(isSigningIn = false, authError = "IDトークンの取得に失敗しました") }
                    return@launch
                }
                authRepository.signInWithGoogleIdToken(idToken)
                    .onSuccess { user ->
                        // 初回ログイン: ローカルをアップロード / 再ログイン: Firestoreで完全上書き
                        val syncResult = firestoreSyncRepository.syncOnLogin(user.uid, repository)
                        syncResult.onFailure { e ->
                            val msg = when {
                                e.message?.contains("PERMISSION_DENIED") == true ->
                                    "Firestore 同期失敗: セキュリティルールが未設定です。Firebase Console > Firestore > ルール を確認してください"
                                e.message?.contains("UNAVAILABLE") == true ||
                                e.message?.contains("NETWORK") == true ->
                                    "Firestore 同期失敗: ネットワークエラー（接続回復後に自動再試行します）"
                                else -> "Firestore 同期失敗: ${e.message}"
                            }
                            _uiState.update { it.copy(authError = msg) }
                        }
                    }
                    .onFailure { e ->
                        _uiState.update { it.copy(authError = e.message) }
                    }
            } catch (e: ApiException) {
                val message = when (e.statusCode) {
                    10 -> "設定エラー: Firebase Console でSHA-1の登録とGoogle認証の有効化を確認してください (code: 10)"
                    12501 -> "サインインがキャンセルされました"
                    7 -> "ネットワークエラーが発生しました。接続を確認してください"
                    else -> "サインインに失敗しました (code: ${e.statusCode})"
                }
                _uiState.update { it.copy(authError = message) }
            } finally {
                _uiState.update { it.copy(isSigningIn = false) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
        }
    }

    fun dismissAuthError() {
        _uiState.update { it.copy(authError = null) }
    }

    /**
     * サインイン済みで未同期（PENDING/FAILED）セッションがあれば SyncWorker を起動する。
     * ホーム画面への復帰時に自動呼び出しされる。
     */
    fun retrySyncIfNeeded() {
        val user = _uiState.value.currentUser ?: return
        val hasPendingOrFailed = _uiState.value.recentSessions.any {
            it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.FAILED
        }
        if (!hasPendingOrFailed) return

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SyncWorker.buildOneTimeRequest()
        )
    }
}
