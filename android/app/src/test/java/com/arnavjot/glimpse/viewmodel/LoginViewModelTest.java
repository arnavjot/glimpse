package com.arnavjot.glimpse.viewmodel;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Input checks done on the device before anything is sent. */
public class LoginViewModelTest {

    @Test
    public void validInputPasses() {
        assertThat(LoginViewModel.checkEmailAndPassword("a@example.com", "correct horse")).isNull();
        assertThat(LoginViewModel.checkHandle("arnav.k_1")).isNull();
    }

    @Test
    public void badEmailsAreCaught() {
        assertThat(LoginViewModel.checkEmailAndPassword("no-at-sign", "correct horse")).contains("email");
        assertThat(LoginViewModel.checkEmailAndPassword("a@b", "correct horse")).contains("email");
    }

    @Test
    public void shortPasswordsAreCaught() {
        assertThat(LoginViewModel.checkEmailAndPassword("a@example.com", "short")).contains("8 characters");
    }

    @Test
    public void handlesMatchTheServersRule() {
        assertThat(LoginViewModel.checkHandle("ab")).isNotNull();
        assertThat(LoginViewModel.checkHandle("has space")).isNotNull();
        assertThat(LoginViewModel.checkHandle("x".repeat(31))).isNotNull();
    }
}
