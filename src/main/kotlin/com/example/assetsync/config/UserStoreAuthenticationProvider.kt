package com.example.assetsync.config

import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InternalAuthenticationServiceException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * HTTP Basic against the user store, with [UserStoreCache] as Spring Security's user cache. A
 * wrong password for a user the cache served because the store could not be read gets the answer
 * of any user the store cannot look up, that the store is unavailable (`503`), rather than bad
 * credentials (`401`); otherwise an outage would tell which usernames were used lately.
 */
class UserStoreAuthenticationProvider(
    userDetailsService: UserDetailsService,
    passwordEncoder: PasswordEncoder,
    userCache: UserStoreCache,
) : DaoAuthenticationProvider(userDetailsService) {

    init {
        setPasswordEncoder(passwordEncoder)
        setUserCache(userCache)
    }

    override fun additionalAuthenticationChecks(userDetails: UserDetails, authentication: UsernamePasswordAuthenticationToken) {
        try {
            super.additionalAuthenticationChecks(userDetails, authentication)
        } catch (exception: BadCredentialsException) {
            if (userDetails is OutageFallbackUser) {
                throw InternalAuthenticationServiceException("The user store could not be read.", userDetails.cause)
            }
            throw exception
        }
    }
}
