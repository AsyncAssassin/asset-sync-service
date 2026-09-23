package com.example.assetsync.config

import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Refuses to start `prod` together with `demo`, `local`, or `test`. `demo` seeds users with public
 * passwords and a fake dataset and opens the chain simulator; `local` and `test` turn
 * authentication off. None of that may reach a prod deployment through a mistyped profile list.
 * As a bean factory post-processor the guard runs before any other bean is created, so the context
 * fails before the demo seeder, a scheduler, or the web server could run, with a message that names
 * the active profiles instead of whichever missing bean the combination would trip over first.
 */
@Component
@Profile("prod & (demo | local | test)")
class ProfileCombinationGuard : BeanFactoryPostProcessor {

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val activeProfiles = beanFactory.getBean(Environment::class.java).activeProfiles.toList()
        throw IllegalStateException(
            "The prod profile cannot be combined with demo, local, or test (active profiles: $activeProfiles): " +
                "demo seeds users with public passwords and opens the chain simulator, and local and test turn " +
                "authentication off.",
        )
    }
}
