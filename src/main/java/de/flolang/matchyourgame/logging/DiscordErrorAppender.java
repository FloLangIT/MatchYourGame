package de.flolang.matchyourgame.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscordErrorAppender extends AppenderBase<ILoggingEvent> {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        DiscordErrorAppender appender = new DiscordErrorAppender();
        appender.setName("DISCORD_ERROR");
        appender.setContext(context);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) return;
        String throwable = event.getThrowableProxy() == null
                ? null : ThrowableProxyUtil.asString(event.getThrowableProxy());
        DiscordLogService.error(event.getLoggerName(), event.getFormattedMessage(), throwable);
    }
}
