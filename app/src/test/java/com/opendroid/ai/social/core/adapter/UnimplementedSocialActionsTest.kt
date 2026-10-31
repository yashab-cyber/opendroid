package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.SocialCredentials
import com.opendroid.ai.social.domain.model.SocialPlatform
import com.opendroid.ai.social.domain.model.SocialPost
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YouTube publishing and YouTube/LinkedIn replies have no API call behind them. With a
 * real token they used to report success, so the post was marked PUBLISHED (and the
 * reply SENT) although nothing reached the platform (#93). They must fail instead.
 */
class UnimplementedSocialActionsTest {

    private val calls = AtomicInteger()
    private val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            calls.incrementAndGet()
            chain.proceed(chain.request())
        })
        .build()

    @Test
    fun `publishing to YouTube with a real token fails instead of faking success`() = runBlocking {
        val result = YouTubeAdapter(client).publishPost(credentials(SocialPlatform.YOUTUBE), post(SocialPlatform.YOUTUBE))

        assertTrue("Expected a failure, got $result", result.isFailure)
        assertEquals(0, calls.get())
    }

    @Test
    fun `replying on YouTube with a real token fails instead of faking success`() = runBlocking {
        val result = YouTubeAdapter(client).replyToComment(credentials(SocialPlatform.YOUTUBE), "comment-1", "thanks!")

        assertTrue("Expected a failure, got $result", result.isFailure)
        assertEquals(0, calls.get())
    }

    @Test
    fun `replying on LinkedIn with a real token fails instead of faking success`() = runBlocking {
        val result = LinkedInAdapter(client).replyToComment(credentials(SocialPlatform.LINKEDIN), "comment-1", "thanks!")

        assertTrue("Expected a failure, got $result", result.isFailure)
        assertEquals(0, calls.get())
    }

    private fun credentials(platform: SocialPlatform) = SocialCredentials(
        accountId = "acc-1",
        platform = platform,
        accessToken = "ya29.a-real-looking-oauth-token"
    )

    private fun post(platform: SocialPlatform) = SocialPost(
        id = "post-1",
        accountId = "acc-1",
        platform = platform,
        content = "Hello"
    )
}
