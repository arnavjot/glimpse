# Source videos

Put real clips here (`.mp4` / `.mov`), then rebuild the library (see the main README,
"Use real videos"). They are converted to vertical 720x1280 HLS, cut to 15 seconds,
with sound kept, and play in file-name order (`01-ocean.mp4`, `02-city.mp4`, ...).

Titles and creators come from `credits.csv` (copy `credits.example.csv`); without it,
from the file name.

Free, properly licensed sources: pexels.com/videos and pixabay.com/videos (search,
then filter Orientation: Portrait). Their licenses allow using clips in an app, but not
redistributing them, so the video files here are git-ignored. Credit the creators
anyway in `credits.csv`: it's shown in the app.
