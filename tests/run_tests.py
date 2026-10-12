"""Executa os testes Java sem interface no Linux e no Windows, após mvn package."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/java-cad-reader-1.0.0.jar"
TESTS = (
    "ReaderIntegrationTest", "ReaderUpdatesTest", "ReaderBatchTest",
    "ReaderMessageFormatTest", "ReaderParticipantsTest", "ReaderClearPendingTest",
    "ReaderHttpTransportTest", "ReaderBotTest", "ReaderBotGatewayTest",
)


def main():
    if not JAR.is_file():
        raise SystemExit("JAR ausente: execute mvn package antes dos testes.")
    with tempfile.TemporaryDirectory(prefix="cad-reader-tests-") as directory:
        sources = sorted(str(p) for p in (ROOT / "tests").glob("*.java"))
        subprocess.run(["javac", "--release", "11", "-encoding", "UTF-8",
                        "-cp", str(JAR), "-d", directory, *sources], check=True)
        classpath = os.pathsep.join((str(JAR), directory))
        for name in TESTS:
            print("Executando " + name, flush=True)
            subprocess.run(["java", "-Djava.awt.headless=true", "-cp", classpath,
                            "br.gov.mg.sids.reader." + name], check=True, timeout=120)


if __name__ == "__main__":
    main()
