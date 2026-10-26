from app import create_app

app = create_app()

if __name__ == "__main__":
    # پورت 5001 تا با سرویس‌های نورا (3000/8000) تداخل نداشته باشد.
    app.run(host="0.0.0.0", port=5001)
