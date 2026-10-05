CC      ?= gcc
CFLAGS  ?= -O2 -Wall -Wextra -std=c11
CPPFLAGS += -Iinclude
LDFLAGS ?=
PREFIX  ?= .
BINDIR  := $(PREFIX)/bin

.PHONY: all clean install

all: bin/acus_driver

bin/acus_driver: src/acus_driver.c include/acus_protocol.h
	mkdir -p bin
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ src/acus_driver.c $(LDFLAGS)

clean:
	rm -f bin/acus_driver

install: bin/acus_driver
	mkdir -p $(BINDIR)
	cp bin/acus_driver $(BINDIR)/
