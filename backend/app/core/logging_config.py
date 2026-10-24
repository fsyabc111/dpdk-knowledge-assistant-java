"""Unified logging configuration for the assistant application.

The application logs through the ``assistant`` logger as single-line JSON so
log shippers and simple ``grep`` both work. Stdout is always active (container
convention); a rotating file handler is added when ``LOG_FILE`` is set.
"""
from __future__ import annotations

import json
import logging
from logging.handlers import RotatingFileHandler
from typing import Any


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "time": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
        }
        message = record.getMessage()
        # Structured events (e.g. chat_trace) are logged as a JSON string;
        # merge them top-level so existing field-based parsing keeps working.
        try:
            decoded = json.loads(message)
        except (ValueError, TypeError):
            decoded = None
        if isinstance(decoded, dict):
            payload.update(decoded)
        else:
            payload["message"] = message
        for key in ("event", "request_id", "answer_status", "reason", "release_id", "error_type", "channel",
                    "tokens", "duration_ms", "stage"):
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def setup_logging(settings) -> logging.Logger:
    """Configure the application logger once; safe to call repeatedly."""
    logger = logging.getLogger("assistant")
    if logger.handlers:
        logger.setLevel(settings.log_level.upper())
        return logger
    logger.setLevel(settings.log_level.upper())
    logger.propagate = False

    formatter = JsonFormatter()
    stream_handler = logging.StreamHandler()
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)

    if settings.log_file:
        file_handler = RotatingFileHandler(
            settings.log_file,
            maxBytes=settings.log_max_bytes,
            backupCount=settings.log_backup_count,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    return logger


def get_logger() -> logging.Logger:
    """Module-level accessor; falls back to a basic logger before setup."""
    logger = logging.getLogger("assistant")
    if not logger.handlers:
        logger.addHandler(logging.NullHandler())
    return logger
