from . import greeting as g
from .greeting import Salutation


def main():
    s = Salutation()
    print(s.salute("smoke"))
    return g.format_salutation("again")
