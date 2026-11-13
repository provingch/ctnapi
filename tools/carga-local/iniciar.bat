@echo off
rem Herramienta de carga de datos de ctnapi. Requiere Python 3 (python.org) y, para importar planillas, LibreOffice.
cd /d "%~dp0"
where py >nul 2>nul && (py -3 server.py) || (python server.py)
pause
