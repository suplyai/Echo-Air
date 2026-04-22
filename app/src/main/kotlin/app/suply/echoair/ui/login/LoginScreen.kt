package app.suply.echoair.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.suply.echoair.R

@Composable
fun LoginScreen(onAuthenticated: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    if (state.success) {
        onAuthenticated()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringRes(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringRes(R.string.login_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringRes(R.string.login_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { vm.submit(email.trim(), password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading && email.isNotBlank() && password.isNotBlank()
        ) {
            if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringRes(R.string.login_submit))
        }
    }
}

@Composable
private fun stringRes(id: Int) = androidx.compose.ui.res.stringResource(id)
