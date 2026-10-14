"""Greeting helpers."""


class Salutation:
    """Formats a salutation."""

    def salute(self, name: str) -> str:
        return format_salutation(name)


def format_salutation(name):
    return f"Hello, {name}"
