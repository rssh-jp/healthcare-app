package com.healthcare.app.ui.screen.settings

import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseUser
import com.healthcare.app.data.repository.AuthRepository
import com.healthcare.app.data.repository.FirestoreSyncRepository
import com.healthcare.app.data.repository.WalkingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val currentUser: FirebaseUser? = null,
    val isSigningIn: Boolean = false,
    val authError: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val walkingRepository: WalkingRepository,
    private val firestoreSyncRepository: FirestoreSyncRepository,
    val googleSignInClient: GoogleSignInClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(currentUser = authRepository.currentUser))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
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
                        val syncResult = firestoreSyncRepository.syncOnLogin(user.uid, walkingRepository)
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
}
