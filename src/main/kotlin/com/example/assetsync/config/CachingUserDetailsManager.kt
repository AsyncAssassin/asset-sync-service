package com.example.assetsync.config

import com.example.assetsync.application.isDatabaseFailure
import org.slf4j.LoggerFactory
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.provisioning.UserDetailsManager

/**
 * The user store behind HTTP Basic: [delegate], the database, with [cache] in front of it for
 * Spring Security. Every load reads the database and fills the cache; when the database cannot be
 * read, a user the cache still holds stands in for it as an [OutageFallbackUser]. A write through
 * this manager removes the user from the cache at once.
 */
class CachingUserDetailsManager(
    private val delegate: UserDetailsManager,
    private val cache: UserStoreCache,
) : UserDetailsManager {

    private val logger = LoggerFactory.getLogger(CachingUserDetailsManager::class.java)

    override fun loadUserByUsername(username: String): UserDetails {
        val writesBefore = cache.writeCount()
        try {
            val loaded = delegate.loadUserByUsername(username)
            cache.store(username, loaded, writesBefore)
            return loaded
        } catch (exception: UsernameNotFoundException) {
            cache.forget(username)
            throw exception
        } catch (exception: RuntimeException) {
            val fallback = if (exception.isDatabaseFailure()) cache.outageFallback(username) else null
            if (fallback == null) {
                throw exception
            }
            logger.warn("user_store_unavailable_serving_cached_user error={}", exception.javaClass.simpleName)
            return OutageFallbackUser(fallback, exception)
        } finally {
            cache.rereadDone(username)
        }
    }

    override fun createUser(user: UserDetails) {
        delegate.createUser(user)
        cache.removeUserFromCache(user.username)
    }

    override fun updateUser(user: UserDetails) {
        delegate.updateUser(user)
        cache.removeUserFromCache(user.username)
    }

    override fun deleteUser(username: String) {
        delegate.deleteUser(username)
        cache.removeUserFromCache(username)
    }

    /** Changes the password of the current user, whom the delegate finds in the security context. */
    override fun changePassword(oldPassword: String?, newPassword: String?) {
        delegate.changePassword(oldPassword, newPassword)
        cache.removeAll()
    }

    override fun userExists(username: String): Boolean = delegate.userExists(username)
}

/** A cached user served because the user store could not be read; [cause] is why it could not. */
class OutageFallbackUser(user: UserDetails, val cause: RuntimeException) :
    User(
        user.username,
        user.password,
        user.isEnabled,
        user.isAccountNonExpired,
        user.isCredentialsNonExpired,
        user.isAccountNonLocked,
        user.authorities,
    )
