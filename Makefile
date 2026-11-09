JAVAC = javac
JAVA = java
SRC_DIR = src
BIN_DIR = bin
CONFIG = config.conf
SOURCES = $(wildcard $(SRC_DIR)/*.java)

.PHONY: all build run test clean re help

all: build

build:
	@mkdir -p $(BIN_DIR)
	@$(JAVAC) -d $(BIN_DIR) $(SOURCES)
	@echo "Build complete. Bytecode stored in $(BIN_DIR)/"

run: build
	@echo "Starting LocalServer with $(CONFIG)..."
	@$(JAVA) -cp $(BIN_DIR) Main $(CONFIG)

test:
	@echo "Running full test suite..."
	@python3 test_suite.py

clean:
	@rm -rf $(BIN_DIR) *.class $(SRC_DIR)/*.class
	@echo "Cleaned compiled bytecode."

re: clean build

help:
	@echo "LocalServer Makefile Commands:"
	@echo "  make        - Compile Java sources into $(BIN_DIR)/"
	@echo "  make run    - Compile and start LocalServer"
	@echo "  make test   - Run the 42-test automated audit test suite"
	@echo "  make clean  - Remove compiled bytecode and $(BIN_DIR)/"
	@echo "  make re     - Rebuild from scratch"

